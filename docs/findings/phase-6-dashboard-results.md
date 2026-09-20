# Phase 6 — Security Posture Dashboard

Evidence that findings from every gate reach one place and are reported
honestly, captured from a real end-to-end sync rather than described as
intended behaviour.

## End-to-end sync output

Against a clean database, pulling from live GitHub Actions runs
`35493010012` (Security Scan Gate) and `35493010002` (Deploy-Time
Admission Gate), the live Minikube cluster, and the LocalStack audit
bucket:

```
Syncing into http://localhost:8090

Checkov (GitHub Actions artifact)
  CHECKOV    57 passed    41 failed   41 findings ingested    0 resolved  [COMPLETE]
Trivy (GitHub Actions artifact)
  TRIVY       0 passed     7 failed    7 findings ingested    0 resolved  [COMPLETE]
OPA / conftest (GitHub Actions artifact)
  OPA         0 passed     4 failed    4 findings ingested    0 resolved  [COMPLETE]
Kyverno (live cluster)
  (skipped system namespaces: kube-system, kyverno)
  KYVERNO     0 passed     0 failed    0 findings ingested    0 resolved  [INCONCLUSIVE]
Remediations (LocalStack S3 audit bucket)
  2 records found, 2 new, 0 already ingested
```

Resulting dashboard state:

| Metric | Value |
|---|---|
| Compliance score | **56%** — 45 open of 102 policy checks |
| Open vulnerabilities | **7** (Trivy, excluded from the score) |
| Mean time to remediate | **110s** across 2 automated fixes |

Scanner freshness: Checkov, Trivy and OPA `ok`; Kyverno `inconclusive`.

## What the numbers actually say

**OPA reports 4 failures, down from the 8 recorded in Phase 4.** The four
S3 public-access findings are gone because that bucket was hardened in
Phase 5. What remains is the IAM wildcard policy and the two open
security-group ports, still seeded deliberately. The dashboard is
tracking real remediation progress, not a fixed snapshot.

**Kyverno is INCONCLUSIVE rather than 0%.** Its admission gate is still
blocking the deployment from Phase 4b, so nothing is running in the
`northbound` namespace for its background scan to evaluate. "Nothing to
scan" is not "everything is clean", and the dashboard refuses to
conflate them: an inconclusive source contributes nothing to the
denominator instead of dragging the score toward zero.

**56% is a real measurement.** The seeded flaws are genuinely present and
genuinely counted.

## Every path is a pull

No component pushes into the dashboard, and none of them knows it
exists:

| Source | Mechanism |
|---|---|
| Checkov, Trivy, OPA | `gh run download` of CI artifacts |
| Kyverno | `kubectl get policyreport,clusterpolicyreport -A -o json` |
| Remediations | JSON objects read from `s3://northbound-audit-logs/remediations/` |

This is a direct consequence of Phase 5's lesson: a Lambda inside
LocalStack could not reliably reach the host, and a GitHub-hosted runner
certainly cannot. Pulling removes the question entirely.

## Safeguards, and the failures that proved they work

**An unparseable artifact is refused, not submitted as an empty scan.**
Before the workflow emitted JSON, the sync script read
`checkov-results.sarif` — valid JSON, wrong shape — found no `summary`
block, and reported 0 passed / 0 failed. That would have been recorded
as a conclusive clean run. The script now refuses:

```
  ! checkov-results.sarif has no 'summary' block — this looks like SARIF, not Checkov JSON.
    The workflow needs output_format: cli,json. Skipping rather than submitting an empty scan.
```

**A scanner reporting zero checks never resolves existing findings.**
Verified against the live API: an empty run returns
`{"status":"INCONCLUSIVE","sweepSkipped":true}` and all existing findings
survive. Without this, a crashed scanner would render as a perfect
compliance score — the worst failure mode a security dashboard has,
because nobody investigates a green board.

**An inconclusive run does not move the score.** Found by testing, not
by review: the score initially fell from 94% to 63% when a scanner
crashed, because the denominator used each source's *latest* run rather
than its latest *completed* one. The real posture had not changed. The
denominator now uses the last conclusive measurement, so a broken
scanner moves the freshness indicator and nothing else.

**Impossible remediation durations are excluded from MTTR.** A record
whose fix predated its trigger produced a reported MTTR of **-8s**. Such
records are now excluded from the average and the count is surfaced on
the page rather than silently dropped.

**Re-syncing cannot double-count.** Findings dedupe on
`SHA256(source + resource + ruleId)`; remediations dedupe on the S3
object key. A second run reports `0 new, 2 already ingested`.

**The resolution sweep is scoped per source.** A clean Checkov run cannot
resolve Kyverno's findings, and vice versa.

12 tests cover these paths, including regression tests for the 94%→63%
score bug and the negative-MTTR bug.

## Deliberate scoping decisions

**Trivy is excluded from the compliance score.** It is a vulnerability
scanner: it reports CVEs found, with no meaningful "checks passed"
count. Including it would mean inventing a denominator, so its findings
are surfaced as a separate vulnerability count instead.

**Kyverno's system namespaces are skipped by default.** Its
ClusterPolicies match Pods cluster-wide, so background scans cover
Kubernetes' own control plane — 24 reports across `kube-system` and
`kyverno`, describing infrastructure this project neither owns nor
controls. Left in, they would dominate the dashboard. Pass
`--include-system-namespaces` to include them.

## Known limitation

One drift injection produced **two** remediation records. The Lambda ran
twice for a single triggering event, and EventBridge assigned the two
deliveries different event IDs (`2fda7cd9…` and `e23496df…`), so keying
the S3 object on the event ID did not deduplicate them. "2 automated
fixes recorded" therefore overstates one actual drift event. Keying on
the event ID handles redelivery of the *same* event; it does not handle
the platform minting a new ID for what is logically one occurrence.
