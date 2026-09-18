# Phase 5 — Event-Driven Remediation Findings

Evidence that drift in the target infrastructure is detected, routed, and
automatically corrected — captured from real runs, including an honest
account of what LocalStack could and could not do.

## The full chain, end to end

`scripts/inject-drift.sh` output, unedited:

```
== 1. Injecting drift: making northbound-analytics-reports public (bypassing Terraform) ==
   current state:
{
    "BlockPublicAcls": false,
    "IgnorePublicAcls": false,
    "BlockPublicPolicy": false,
    "RestrictPublicBuckets": false
}

== 2. Publishing the Config compliance-change event ==
{
    "FailedEntryCount": 0,
    "Entries": [ { "EventId": "adac4afc-4df5-450d-a4d6-28a7889834a6" } ]
}

== 3. Waiting for the remediation Lambda to restore the baseline ==
   remediated after ~4s
{
    "BlockPublicAcls": true,
    "IgnorePublicAcls": true,
    "BlockPublicPolicy": true,
    "RestrictPublicBuckets": true
}
```

**Measured time from event to remediation: under one second** (event at
`04:23:12.728`, remediation logged at `04:23:13.377`).

### Proof the Lambda was invoked by EventBridge, not directly

The script never calls `lambda invoke`. The Lambda's own log shows it
received an event carrying envelope fields the script never sent —
`version`, `account`, `time`, `region`, `resources`, and an `id` matching
the `EventId` that `PutEvents` returned:

```
Received event: {"version": "0", "id": "adac4afc-4df5-450d-a4d6-28a7889834a6",
"detail-type": "Config Rules Compliance Change", "source": "aws.config",
"account": "000000000000", "time": "2026-09-18T04:23:12Z", "region": "us-east-1",
"resources": [], "detail": {"resourceId": "northbound-analytics-reports", ...}}
Bucket northbound-analytics-reports is NON_COMPLIANT against rule s3-bucket-public-read-prohibited — restoring public access block.
Remediated northbound-analytics-reports: public access block restored to secure baseline.
```

EventBridge added those fields while matching the rule pattern defined in
`infra/environments/dev/remediation.tf` and routing to the target.

## What is real vs. simulated

| Step | Status locally |
|---|---|
| Drift injected by direct AWS CLI call, bypassing Terraform | real |
| Config evaluates the rule and emits the event | **simulated** — see below |
| EventBridge matches the rule pattern | real |
| EventBridge routes to the Lambda target | real |
| Lambda executes with its scoped IAM role | real |
| Lambda calls S3 and changes actual bucket state | real |

Only the event's *emission* is stood in for. Everything downstream of it
is genuinely exercised.

## Why: LocalStack mocks AWS Config

Verified empirically and confirmed by LocalStack's own documentation —
"LocalStack will currently not record any configuration changes to service
resources":

| Config capability | Result |
|---|---|
| Create recorder / delivery channel / rules | works |
| `start-config-rules-evaluation` | `has not been implemented` |
| `describe-compliance-by-config-rule` | `has not been implemented` |
| `get-compliance-details-by-config-rule` | `has not been implemented` |
| `list-discovered-resources` | returns `[]` |

**This is not a licensing gap.** Ruled out explicitly: the auth token is
present and reaching the container, the health endpoint reports
`edition: pro`, and the logs confirm an active trial license. Config
behaves identically with Pro active.

A second fallback was also tested and ruled out: LocalStack does not put
S3 API calls onto the EventBridge bus either, so a CloudTrail-driven
event pattern is equally unavailable. A scheduled EventBridge rule *was*
confirmed to fire a Lambda, and remains a viable alternative trigger.

## Bugs found and fixed while building this

1. **Terraform silently targeting real AWS.** `provider.tf` had no
   LocalStack endpoints for Config, CloudTrail, Lambda, EventBridge or
   Logs, so those API calls left LocalStack entirely and real AWS
   rejected the fake credentials with `UnrecognizedClientException`.
   Nothing was created there, but the failure mode is silent — any
   service missing from that endpoints block goes to real AWS.
2. **Lambda hung on every S3 call, twice.** Inside a Lambda container,
   neither `localhost` nor `localhost.localstack.cloud` reaches
   LocalStack; both caused a 30s timeout. Resolved by dumping the
   container's own environment, which revealed `LOCALSTACK_HOSTNAME` —
   `handler.py` now builds its endpoint from that rather than a guessed
   or hardcoded host.
3. **A test that proved nothing.** One early run returned
   `remediated: true` but had written `true` over `true`, because a
   `terraform apply` had already reverted the manual break. Re-run
   properly as break → confirm broken → trigger → confirm fixed.
