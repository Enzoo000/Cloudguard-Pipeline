# Phase 4 — Admission Gate Findings

Evidence that both deploy-time admission gates (Terraform plan and
Kubernetes manifests) actually block bad changes, captured from real
runs rather than described as intended behavior.

## Phase 4a — OPA / Conftest (terraform plan)

Verified end to end on real GitHub Actions (`.github/workflows/admission-gate.yml`):
LocalStack starts, Terraform bootstraps a fresh remote-state backend,
plans the dev environment, and `conftest` evaluates the actual plan.

**Result: 8 tests, 0 passed, 8 failures.**

```
FAIL - aws_iam_role_policy.app: IAM policy statement grants wildcard Action "*"
FAIL - aws_iam_role_policy.app: IAM policy statement grants wildcard Resource "*"
FAIL - aws_s3_bucket_public_access_block.reports: block_public_acls must be true, found false
FAIL - aws_s3_bucket_public_access_block.reports: block_public_policy must be true, found false
FAIL - aws_s3_bucket_public_access_block.reports: ignore_public_acls must be true, found false
FAIL - aws_s3_bucket_public_access_block.reports: restrict_public_buckets must be true, found false
FAIL - aws_security_group.app: ingress on port 22 is open to 0.0.0.0/0
FAIL - aws_security_group.app: ingress on port 8080 is open to 0.0.0.0/0
```

Notably, the security-group policy (`policies/opa/network.rego`) catches
**both** port 8080 and port 22 — closing the exact gap Checkov's
port-specific checks left open in Phase 3, where port 8080 alone never
triggered a dedicated finding.

## Phase 4b — Kyverno (Kubernetes admission)

Kyverno installed via Helm into a real Minikube cluster; all 4 controller
pods confirmed `Running`/`Ready` before any policy or workload was
applied, to rule out a webhook timeout during initialization.

```
$ kubectl get clusterpolicy
NAME                      ADMISSION   BACKGROUND   READY   MESSAGE
disallow-latest-tag       true        true         True    Ready
require-resource-limits   true        true         True    Ready
```

**Live enforcement test:** the running deployment was deleted and
re-applied unchanged (still carrying its two seeded flaws). Kyverno's
admission webhook rejected it outright, at the API server layer, before
a pod was ever created:

```
Error from server: error when creating "k8s/base/deployment.yaml": admission webhook "validate.kyverno.svc-fail" denied the request:

resource Deployment/northbound/northbound-analytics was blocked due to the following policies

disallow-latest-tag:
  autogen-require-image-tag: 'validation error: Images must not use the ":latest" tag or be untagged - pin to a specific version. rule autogen-require-image-tag failed at path /spec/template/spec/containers/0/image/'
require-resource-limits:
  autogen-require-requests-and-limits: 'validation error: Every container must set resources.requests and resources.limits (cpu and memory). rule autogen-require-requests-and-limits failed at path /spec/template/spec/containers/0/resources/limits/'
```

**Current cluster state, kept intentionally:** no pod is running in the
`northbound` namespace. This is deliberate — a deployment that
successfully ran despite its flaws would prove nothing; a deployment
that gets rejected at the API server, with the exact violated rule named
in the error, is concrete evidence the gate isn't decorative.

## Supporting evidence: the NetworkPolicy default-deny is real

While verifying `k8s/base`/`k8s/policy` (before Kyverno was installed), a
plain test pod's `nslookup` for the app's Service timed out entirely
until an explicit DNS-egress allow rule was added — proof the
default-deny NetworkPolicy blocks real traffic, not just traffic that
was never going to be sent. A subsequent test, granted a temporary,
narrowly-scoped egress rule, successfully reached the app
(`{"status":"UP"}`) through the Service, confirming the ingress rule
works precisely as configured before the temporary rule was removed.
