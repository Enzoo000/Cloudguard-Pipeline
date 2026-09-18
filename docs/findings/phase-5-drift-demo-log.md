# Phase 5 — Drift Demo Log

Raw, unedited output from a single run of `scripts/inject-drift.sh`
against LocalStack. Three artifacts: the event published, the Lambda's
execution log, and the bucket state before and after.

Correlation ID for this run: `fe6721f7-5285-4819-b32a-f6e21ce2fcee`
(returned by `PutEvents`, and visible again in the Lambda's received
event — the two are the same event).

---

## 1. Simulated drift event payload

Published to EventBridge via `PutEvents`, matching the schema AWS Config
emits on real AWS:

```json
[
  {
    "Source": "aws.config",
    "DetailType": "Config Rules Compliance Change",
    "Detail": "{\"resourceId\": \"northbound-analytics-reports\", \"resourceType\": \"AWS::S3::Bucket\", \"configRuleName\": \"s3-bucket-public-read-prohibited\", \"awsRegion\": \"us-east-1\", \"newEvaluationResult\": {\"complianceType\": \"NON_COMPLIANT\"}}"
  }
]
```

Accepted by EventBridge:

```json
{
    "FailedEntryCount": 0,
    "Entries": [
        { "EventId": "fe6721f7-5285-4819-b32a-f6e21ce2fcee" }
    ]
}
```

---

## 2. Lambda remediation execution log

```
START RequestId: b1b50d5d-5d13-41f4-9962-4f4a1633fd0c Version: $LATEST

[INFO] 2026-09-18T04:34:12.732Z  Received event: {"version": "0", "id": "fe6721f7-5285-4819-b32a-f6e21ce2fcee",
"detail-type": "Config Rules Compliance Change", "source": "aws.config", "account": "000000000000",
"time": "2026-09-18T04:34:01Z", "region": "us-east-1", "resources": [],
"detail": {"resourceId": "northbound-analytics-reports", "resourceType": "AWS::S3::Bucket",
"configRuleName": "s3-bucket-public-read-prohibited", "awsRegion": "us-east-1",
"newEvaluationResult": {"complianceType": "NON_COMPLIANT"}}}

[INFO] 2026-09-18T04:34:12.739Z  Bucket northbound-analytics-reports is NON_COMPLIANT against rule s3-bucket-public-read-prohibited — restoring public access block.
[INFO] 2026-09-18T04:34:12.925Z  Found credentials in environment variables.
[INFO] 2026-09-18T04:34:37.154Z  Remediated northbound-analytics-reports: public access block restored to secure baseline.

END RequestId: b1b50d5d-5d13-41f4-9962-4f4a1633fd0c
REPORT RequestId: b1b50d5d-5d13-41f4-9962-4f4a1633fd0c  Duration: 24832.74 ms  Billed Duration: 24833 ms  Memory Size: 128 MB  Max Memory Used: 128 MB  Init Duration: 4769.90 ms
```

Two things worth reading closely here:

- **The Lambda was invoked by EventBridge, not directly.** The received
  event carries `version`, `id`, `account`, `time`, `region` and
  `resources` — envelope fields the script never sent. EventBridge added
  them while matching the rule pattern and routing to the target. The
  `id` matches the `EventId` from `PutEvents` above.
- **Timing varies run to run.** This run took 24.8s (with a 4.8s cold
  start); an earlier run of the same chain completed in under a second
  on a warm container. Recorded as measured rather than quoting only the
  faster result.

---

## 3. State verification

Before drift — the secure baseline defined in
`infra/environments/dev/s3.tf`:

```json
{
    "BlockPublicAcls": true,
    "IgnorePublicAcls": true,
    "BlockPublicPolicy": true,
    "RestrictPublicBuckets": true
}
```

After drift injected via direct AWS CLI call, bypassing Terraform:

```json
{
    "BlockPublicAcls": false,
    "IgnorePublicAcls": false,
    "BlockPublicPolicy": false,
    "RestrictPublicBuckets": false
}
```

After remediation, without any human action or manual Lambda invoke:

```json
{
    "BlockPublicAcls": true,
    "IgnorePublicAcls": true,
    "BlockPublicPolicy": true,
    "RestrictPublicBuckets": true
}
```

---

## Scope note

The event in section 1 is published by the drift script because
LocalStack mocks AWS Config and never evaluates rules or emits
compliance events. Everything after that event — EventBridge pattern
matching, routing, Lambda invocation, and the S3 API call that changed
real state — ran for real. See `phase-5-remediation-results.md` for the
full detail on that limitation and how it was verified.
