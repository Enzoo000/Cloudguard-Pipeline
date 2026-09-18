# infra/environments/dev/audit.tf
#
# The detection half of Phase 5: a destination bucket for audit data,
# AWS Config to evaluate resource state against a rule, and CloudTrail
# to record who changed what. On real AWS, the Config rule below flips
# the reports bucket to NON_COMPLIANT when its public access block is
# removed, and Config emits a compliance-change event that EventBridge
# routes to the remediation Lambda (see remediation.tf).
#
# LOCALSTACK LIMITATION — READ BEFORE TRUSTING THIS FILE LOCALLY:
# LocalStack only *mocks* AWS Config. These resources create fine, but
# nothing evaluates. Confirmed both empirically and in LocalStack's own
# docs ("LocalStack will currently not record any configuration changes
# to service resources"): start-config-rules-evaluation,
# describe-compliance-by-config-rule and
# get-compliance-details-by-config-rule all return "has not been
# implemented", and list-discovered-resources returns an empty list.
# This is not a licensing gap — it behaves identically with an active
# Pro license (edition: pro, verified).
#
# These resources are kept because they are the correct architecture for
# real AWS, and because the EventBridge rule's pattern is written against
# the genuine Config event schema. Locally, scripts/inject-drift.sh
# publishes that same event via PutEvents to stand in for Config's
# evaluation engine — everything downstream of the event (EventBridge
# pattern matching, routing, Lambda invocation, the S3 API call) is
# exercised for real.

# --- Destination bucket for Config snapshots and CloudTrail logs ---

resource "aws_s3_bucket" "audit_logs" {
  bucket        = "northbound-audit-logs"
  force_destroy = true

  tags = {
    Project = "cloudguard-pipeline"
    Purpose = "config-and-cloudtrail-delivery"
  }
}

resource "aws_s3_bucket_public_access_block" "audit_logs" {
  bucket = aws_s3_bucket.audit_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# --- AWS Config ---

resource "aws_iam_role" "config" {
  name = "northbound-config-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "config.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Project = "cloudguard-pipeline"
  }
}

resource "aws_iam_role_policy" "config" {
  name = "northbound-config-policy"
  role = aws_iam_role.config.id

  # Scoped to what Config actually needs: read resource state, and write
  # its snapshots to the audit bucket. Not a wildcard.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetBucketAcl"]
        Resource = [aws_s3_bucket.audit_logs.arn, "${aws_s3_bucket.audit_logs.arn}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["config:Put*", "s3:GetBucketPublicAccessBlock"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_config_configuration_recorder" "main" {
  name     = "northbound-recorder"
  role_arn = aws_iam_role.config.arn

  recording_group {
    all_supported = true
  }
}

resource "aws_config_delivery_channel" "main" {
  name           = "northbound-delivery-channel"
  s3_bucket_name = aws_s3_bucket.audit_logs.bucket

  depends_on = [aws_config_configuration_recorder.main]
}

resource "aws_config_configuration_recorder_status" "main" {
  name       = aws_config_configuration_recorder.main.name
  is_enabled = true

  depends_on = [aws_config_delivery_channel.main]
}

# The rule that actually notices the drift: AWS-managed check that an
# S3 bucket prohibits public read access. Evaluates on configuration
# change, so flipping the reports bucket's public access block marks it
# NON_COMPLIANT.
resource "aws_config_config_rule" "s3_public_read_prohibited" {
  name = "s3-bucket-public-read-prohibited"

  source {
    owner             = "AWS"
    source_identifier = "S3_BUCKET_PUBLIC_READ_PROHIBITED"
  }

  depends_on = [aws_config_configuration_recorder_status.main]
}

# --- CloudTrail ---

resource "aws_cloudtrail" "main" {
  name                          = "northbound-trail"
  s3_bucket_name                = aws_s3_bucket.audit_logs.bucket
  include_global_service_events = true
  is_multi_region_trail         = false

  tags = {
    Project = "cloudguard-pipeline"
  }

  depends_on = [aws_s3_bucket.audit_logs]
}
