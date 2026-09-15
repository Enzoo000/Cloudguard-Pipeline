# infra/environments/dev/main.tf
#
# Intentionally near-empty on Day 1. This file proves the remote state
# backend works end to end (init succeeds, plan/apply write to the
# LocalStack-hosted S3 state bucket). Day 2 replaces this with real
# IAM/governance modules.

resource "aws_s3_bucket" "day1_smoke_test" {
  bucket = "cloudguard-day1-smoke-test"

  tags = {
    Project = "cloudguard-pipeline"
    Purpose = "day1-backend-verification"
  }
}
