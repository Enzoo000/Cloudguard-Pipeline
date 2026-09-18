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

    logger.info("Remediated %s: public access block restored to secure baseline.", bucket)

    return {
        "remediated": True,
        "bucket": bucket,
        "rule": rule,
        "restored": SECURE_PUBLIC_ACCESS_BLOCK,
    }
