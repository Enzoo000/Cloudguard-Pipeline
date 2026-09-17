# Phase 3 — Scan Gate Findings

Evidence that the pre-deploy scan gate (`.github/workflows/security-scan.yml`)
actually works, captured from real runs — both local (`act`) and live on
GitHub Actions — not just a description of intended behavior.

## Checkov (IaC scan)

**Result: 18 passed, 28 failed.** Confirmed on both local `act` runs and a
live GitHub Actions run.

All three seeded infrastructure flaws are caught:

| Seeded flaw | Check | Resource |
|---|---|---|
| Wildcard IAM policy (`Action: "*"`, `Resource: "*"`) | `CKV2_AWS_40` | `aws_iam_role_policy.app` |
| Public S3 access block disabled | `CKV2_AWS_6` (+ related S3 hardening checks) | `aws_s3_bucket.reports` |
| Unrestricted SSH ingress (`0.0.0.0/0` on port 22) | `CKV_AWS_24` | `aws_security_group.app` |

Confirmed live on GitHub Actions (not just locally):

```
Check: CKV_AWS_24: "Ensure no security groups allow ingress from 0.0.0.0:0 to port 22"
	FAILED for resource: aws_security_group.app
	File: /network.tf:21-55
	Guide: https://docs.prismacloud.io/en/enterprise-edition/policy-reference/aws-policies/aws-networking-policies/networking-1-port-security
```

**Known, deliberate gap:** the security group's port-8080 (application
traffic) ingress rule, also open to `0.0.0.0/0`, does **not** trigger its own
dedicated Checkov finding — Checkov's built-in open-ingress checks are keyed
to specific well-known ports (22, 3389, etc.), not arbitrary application
ports. The port-22 rule above was added specifically to give Checkov
something concrete to catch, confirming this behavior with real tool output
rather than assumption.

## Trivy (image scan)

**Result: 41 findings** (down from an initial 110), image
`northbound-analytics:ci` built from `app/Dockerfile`.

Reduced by:
- Bumping Spring Boot `3.3.4` → `3.5.16` (the `3.3.x` branch had zero tags
  left on the upstream repo — no longer maintained at all, not just behind)
- Bumping `jjwt` `0.12.6` → `0.12.7`
- Adding `ignore-unfixed: true` to the Trivy step, dropping OS-level CVEs
  with no available patch yet

Remaining findings are entirely upstream, not anything seeded intentionally:

- **Alpine OS packages:** `libcrypto3`, `libssl3`, `libexpat`, `openssl`
- **Java dependencies (transitive, via Spring Boot):** `jackson-databind`,
  `log4j-api`, `tomcat-embed-core`

**Known, deliberate finding:** Trivy's secret scanner is enabled by default
but reported **zero** hits on the hardcoded JWT secret in
`application.properties`. A plain UUID-format string doesn't match the
known-pattern signatures (AWS access keys, private key headers, etc.)
pattern-based secret scanners look for. That flaw is only demonstrable via
the path-traversal exploit chain documented in `app/README.md`, not via
Trivy directly.

## Gate behavior

Both jobs fail closed — confirmed on real infrastructure, not simulated:
the workflow run shows both `Checkov (IaC scan)` and `Trivy (image scan)`
with a failing status, and neither is expected to pass until the seeded
flaws are actually remediated (a later phase's job, not this one's).
