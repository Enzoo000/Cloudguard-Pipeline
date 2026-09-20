"""Auto-remediation for S3 buckets that drift to a public-access posture.

Triggered by EventBridge when AWS Config flips a bucket to NON_COMPLIANT
against the s3-bucket-public-read-prohibited rule. Restores the bucket's
public access block to the secure baseline defined in
infra/environments/dev/s3.tf.

This is the "writes fix back" edge in the architecture diagram: the loop
only closes if this actually changes the resource, not just logs about it.
"""

import json
import logging
import os
from datetime import datetime, timezone

import boto3

logger = logging.getLogger()
logger.setLevel(logging.INFO)

# The baseline this function restores — matches s3.tf exactly. If the
# Terraform baseline changes, this must change with it.
SECURE_PUBLIC_ACCESS_BLOCK = {
    "BlockPublicAcls": True,
    "IgnorePublicAcls": True,
    "BlockPublicPolicy": True,
    "RestrictPublicBuckets": True,
}


def _s3_client():
    # Inside a LocalStack Lambda container, "localhost" is the container
    # itself, and localhost.localstack.cloud doesn't resolve there either
    # — both cause the S3 call to hang until the function times out.
    # LOCALSTACK_HOSTNAME is injected with the address that actually
    # reaches LocalStack from in here, so build the endpoint from it
    # rather than hardcoding anything.
    localstack_host = os.environ.get("LOCALSTACK_HOSTNAME")
    if localstack_host:
        return boto3.client("s3", endpoint_url=f"http://{localstack_host}:4566")

    # Real AWS sets neither variable, and boto3 resolves the real
    # endpoint on its own.
    endpoint = os.environ.get("AWS_ENDPOINT_URL")
    if endpoint:
        return boto3.client("s3", endpoint_url=endpoint)
    return boto3.client("s3")


def lambda_handler(event, context):
    logger.info("Received event: %s", json.dumps(event))

    detail = event.get("detail", {})
    bucket = detail.get("resourceId")
    compliance = detail.get("newEvaluationResult", {}).get("complianceType")
    rule = detail.get("configRuleName")

    if not bucket:
        logger.warning("No resourceId in event; nothing to remediate.")
        return {"remediated": False, "reason": "no resourceId in event"}

    if compliance != "NON_COMPLIANT":
        logger.info("Bucket %s is %s; no action needed.", bucket, compliance)
        return {"remediated": False, "reason": f"compliance is {compliance}"}

    logger.info(
        "Bucket %s is NON_COMPLIANT against rule %s — restoring public access block.",
        bucket,
        rule,
    )

    s3 = _s3_client()
    s3.put_public_access_block(
        Bucket=bucket,
        PublicAccessBlockConfiguration=SECURE_PUBLIC_ACCESS_BLOCK,
    )

    remediated_at = datetime.now(timezone.utc)
    logger.info("Remediated %s: public access block restored to secure baseline.", bucket)

    audit_key = _write_audit_record(s3, event, bucket, rule, remediated_at)

    return {
        "remediated": True,
        "bucket": bucket,
        "rule": rule,
        "restored": SECURE_PUBLIC_ACCESS_BLOCK,
        "auditKey": audit_key,
    }


def _write_audit_record(s3, event, bucket, rule, remediated_at):
    """Drop a record of this fix into the audit bucket for the dashboard.

    The dashboard reads these objects rather than receiving a push: a
    Lambda in LocalStack can't reliably reach a process on the host
    (host.docker.internal behaves differently across OSes and Docker
    network modes), and Phase 5 already burned two attempts on exactly
    that class of networking assumption. Writing to S3 keeps the Lambda a
    pure worker with no knowledge of anything outside AWS.

    Failure here is logged but never raised. The fix has already landed
    by this point — failing the invocation would misreport a successful
    remediation and invite a retry that re-does work already done.
    """
    audit_bucket = os.environ.get("AUDIT_BUCKET")
    if not audit_bucket:
        logger.warning("AUDIT_BUCKET not set; skipping audit record.")
        return None

    # Keyed on the event id alone, deliberately without a timestamp.
    # EventBridge can deliver the same event more than once, and this
    # function was observed running twice for a single drift event —
    # with the timestamp in the key that produced two records for one
    # event and skewed the reported MTTR. Keying on the event alone means
    # a redelivery overwrites rather than accumulates: one triggering
    # event, one record.
    event_id = event.get("id", "unknown")
    key = f"remediations/{event_id}.json"

    record = {
        "resource": bucket,
        "rule": rule,
        # When the triggering event was emitted, not when a human noticed
        # — the gap between this and remediatedAt is the reported MTTR.
        "triggeredAt": event.get("time"),
        "remediatedAt": remediated_at.isoformat().replace("+00:00", "Z"),
        "details": "public access block restored to secure baseline",
        "restored": SECURE_PUBLIC_ACCESS_BLOCK,
        "eventId": event_id,
    }

    try:
        s3.put_object(
            Bucket=audit_bucket,
            Key=key,
            Body=json.dumps(record).encode("utf-8"),
            ContentType="application/json",
        )
        logger.info("Wrote audit record s3://%s/%s", audit_bucket, key)
        return key
    except Exception:
        logger.exception("Failed to write audit record; remediation itself succeeded.")
        return None
