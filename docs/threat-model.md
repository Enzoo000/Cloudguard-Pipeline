# Threat Model

A STRIDE pass over what this project actually builds, not a generic
checklist. Every mitigation named here points at a specific file, and
every gap is stated rather than omitted — several controls in this system
are deliberately incomplete, and a threat model that hid that would be
worse than none.

## Scope

Four assets, each with a different exposure:

| Asset | What it is |
|---|---|
| **Target application** | Northbound Analytics (`app/`) — a Spring Boot API with four deliberately seeded vulnerabilities |
| **Cloud infrastructure** | S3, IAM, VPC, security groups in LocalStack (`infra/environments/dev/`) |
| **The pipeline itself** | Scan gate, two admission gates, remediation loop |
| **The reporting layer** | Dashboard and its findings database (`dashboard/`) |

The fourth deserves emphasis: **the security tooling is itself an asset**.
A pipeline that can be bypassed or whose findings can be falsified
provides worse than no protection, because it produces confidence.

---

## S — Spoofing

| Threat | Mitigation | Status |
|---|---|---|
| Attacker authenticates as another Northbound Analytics user | JWT signature verification in `AuthUtil.assertValid` | **Broken by design.** The signing secret is hardcoded in `application.properties`, so anyone who reads it can mint a valid token for any user. Seeded flaw 4. |
| Attacker guesses credentials offline from a stolen database | Password hashing | **Broken by design.** Unsalted MD5 (`LoginController.md5`) is trivially reversible via rainbow tables. Seeded flaw 1. |
| Workload in the cluster impersonates the app to the Kubernetes API | Dedicated ServiceAccount with an empty RBAC Role (`k8s/policy/rbac.yaml`) | Mitigated. The identity exists but grants nothing, so impersonating it yields no API access. |
| Forged findings submitted to the dashboard | — | **Not mitigated.** `POST /api/scans` is unauthenticated. Anyone who can reach port 8090 can inject findings or fabricate a clean run. Acceptable for a local single-user tool; unacceptable if ever exposed. |

## T — Tampering

| Threat | Mitigation | Status |
|---|---|---|
| Infrastructure changed outside version control | CloudTrail records API calls; the remediation Lambda restores the S3 baseline | Mitigated for the one resource the Lambda covers. Every other resource drifts undetected. |
| Malicious or careless Terraform merged | Checkov (`security-scan.yml`) plus OPA/conftest against the plan (`admission-gate.yml`), both fail-closed | Mitigated in CI. **Not enforced at deploy:** no branch protection rule exists, and `terraform apply` is run manually, so a failing gate does not physically prevent a deploy. |
| Unsafe workload pushed to the cluster | Kyverno admission policies, `validationFailureAction: Enforce` | Mitigated, and demonstrated: the deployment is currently rejected at the API server. |
| Container tampered with in transit | Trivy scans the built image | Partially. Vulnerabilities are detected; there is no image signing or provenance attestation. |
| Findings altered after ingestion | — | **Not mitigated.** The H2 database is a local file with no integrity protection. |

## R — Repudiation

| Threat | Mitigation | Status |
|---|---|---|
| Someone changes infrastructure and denies it | CloudTrail trail delivering to `northbound-audit-logs` (`audit.tf`) | Mitigated in principle. The trail is real; attributing an action to a person requires real IAM identities, which a LocalStack environment with `test`/`test` credentials does not have. |
| Automated fix happens with no record | The Lambda writes a JSON record per fix to the audit bucket, ingested by the dashboard | Mitigated, with a known flaw: one drift event produced two records, so counts can overstate. |
| Audit log deleted to hide activity | Lambda IAM grants `s3:PutObject` only, scoped to `remediations/*` — no delete, no read | Mitigated for that principal. The audit bucket has no versioning or object lock, so a broader credential could still erase history. |

## I — Information Disclosure

| Threat | Mitigation | Status |
|---|---|---|
| Customer A reads Customer B's reports | Query scoping in `ReportService` | **Broken by design.** String-concatenated SQL means `x' OR '1'='1` returns every customer's data. Seeded flaw 2. |
| Arbitrary files read from the app server | Path handling in `ReportController` | **Broken by design.** The `/exports/**` wildcard passes the path straight to the filesystem, so `../` escapes the export directory. Seeded flaw 3. |
| Report exports exposed publicly via S3 | Public access block set true on all four flags (`s3.tf`), enforced by Checkov, OPA and the remediation Lambda | Mitigated, and the only control in this project defended at all three checkpoints. |
| Secrets committed to the repository | `.gitignore` excludes `.env` and `.env.*` | Mitigated **after an actual incident**: a LocalStack auth token was committed early on, then rotated and the gap closed. |
| Credentials leaked from the container image | — | **Broken by design.** `application.properties` ships inside the image, and flaw 3 can read it. Notably, Trivy's secret scanner did *not* flag it — a plain UUID matches no known secret signature. |

## D — Denial of Service

| Threat | Mitigation | Status |
|---|---|---|
| One container starves the node | Resource requests and limits | **Not mitigated in the manifest** — this is the seeded flaw Kyverno's `require-resource-limits` policy exists to catch, and currently blocks. |
| App reachable from anywhere on the internet | Security group ingress | **Broken by design.** Ports 22 and 8080 are open to `0.0.0.0/0`. Seeded flaws, caught by both Checkov (`CKV_AWS_24`) and OPA. |
| Lateral movement between pods | Default-deny NetworkPolicy with explicit DNS and app-ingress allows (`k8s/policy/networkpolicy.yaml`) | Mitigated, and verified: a test pod's traffic was blocked until explicitly permitted. |
| Expensive SQL exhausts the database | — | **Not mitigated.** No query timeouts or rate limiting. Flaw 2 makes this reachable. |

## E — Elevation of Privilege

| Threat | Mitigation | Status |
|---|---|---|
| Compromised app assumes broad AWS permissions | IAM role policy | **Broken by design.** `northbound-app-role` grants `Action: "*"` on `Resource: "*"`. Seeded flaw, caught by Checkov `CKV2_AWS_40` and OPA. |
| Container escapes to the host | `runAsNonRoot`, `runAsUser: 100`, `allowPrivilegeEscalation: false`, all capabilities dropped, `seccompProfile: RuntimeDefault` (`k8s/base/deployment.yaml`), plus baseline Pod Security Standards on the namespace | Mitigated. |
| Remediation Lambda abused as a privileged tool | Its role grants only CloudWatch Logs, `s3:PutBucketPublicAccessBlock` on one bucket, and `s3:PutObject` on one prefix | Mitigated. A deliberate contrast with the app role above. |
| AWS Config service role over-permissioned | — | **Not mitigated, and not seeded.** `aws_iam_role_policy.config` in `audit.tf` grants `config:Put*` on `Resource: "*"`. This was written as supporting infrastructure, not as a deliberate flaw, and was caught by this project's own OPA policy during the final demo run. The `config:Put*` actions are awkward to scope to specific resources, but the statement also covers `s3:GetBucketPublicAccessBlock`, which could be narrowed to the two known buckets. Recorded rather than fixed at the end of the project, since changing working infrastructure to close out a phase is how demos break. |
| Attacker disables the scanners to hide findings | The dashboard records a scanner reporting zero checks as `INCONCLUSIVE`, never resolving existing findings, and shows per-source freshness | Mitigated in the reporting layer. Silencing a scanner shows up as stale or inconclusive rather than as a clean score. |

---

## Threats against the pipeline itself

The most interesting attacks target the controls rather than the
application.

**Bypass the gates entirely.** The gates only inspect what passes
through CI. A direct `aws` CLI call reaches the infrastructure without
touching them — which is not hypothetical here, it is exactly what
`scripts/inject-drift.sh` does. Post-deploy detection exists because of
this, and it is the reason checkpoint 3 is not redundant with
checkpoints 1 and 2.

**Make a broken scanner look like a clean one.** If a scanner crashes and
the dashboard treats "no findings reported" as "no findings exist", the
board turns green and nobody investigates. This was the single most
dangerous failure mode identified during design, and it is guarded at
three points: runs reporting zero checks are marked `INCONCLUSIVE`, the
resolution sweep is refused for them, and unparseable artifacts are
rejected instead of submitted. Each guard exists because the failure was
actually observed during development, not anticipated in the abstract.

**Falsify the score.** The compliance score has no authentication in
front of it. Ingestion is trusted because it is local and single-user.
Any real deployment would need authenticated ingestion and signed
scanner output.

**Remove the evidence.** The Lambda cannot delete audit records, but
nothing else prevents their deletion — no bucket versioning, no object
lock, no off-box copy.

---

## Honest summary

Of the four assets, **the application is deliberately indefensible** —
all four of its seeded flaws remain exploitable, because demonstrating
detection requires something real to detect.

**The infrastructure is partially defended.** The S3 exposure is the only
control covered at all three checkpoints; the IAM wildcard and open
security-group ports are detected repeatedly by two independent gates and
deliberately left unfixed as standing proof those gates work.

**The cluster is genuinely defended**, and is the one place where an
attack is stopped rather than merely observed: Kyverno rejects the
non-compliant deployment at the API server, and it stays rejected.

**The reporting layer is defended against silence but not against
forgery** — it will not let a broken scanner masquerade as a clean one,
but it will believe anything it is told.

The largest real gap is that **detection is not enforcement**: no branch
protection rule exists, and deployment is not gated on the checks
passing. Today a developer can watch every gate fail and run
`terraform apply` anyway.
