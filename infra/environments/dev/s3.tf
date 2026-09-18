# infra/environments/dev/s3.tf
#
# Storage for Northbound Analytics' report exports (the target app's
# GET /exports/{filename} feature).
#
# This bucket's public-access misconfiguration was seeded deliberately
# through Phases 3 and 4a, and both gates caught it for real — Checkov
# flagged CKV2_AWS_6 on GitHub Actions, and the OPA policy in
# policies/opa/s3.rego failed the terraform plan on all four flags. With
# that proven, the flaw is now fixed, exactly as the original comment
# said it would be.
#
# It's fixed here for a second reason too: Phase 5's drift demo needs a
# genuinely secure baseline to drift *away* from. A bucket that starts
# public can't demonstrate detection of an unauthorized change — it was
# already wrong before anyone touched it.

resource "aws_s3_bucket" "reports" {
  bucket = "northbound-analytics-reports"

  tags = {
    Project = "cloudguard-pipeline"
    Target  = "northbound-analytics"
  }
}

resource "aws_s3_bucket_public_access_block" "reports" {
  bucket = aws_s3_bucket.reports.id

  # The secure baseline. Phase 5's manual-drift demo flips these to
  # false via the AWS CLI (bypassing Terraform entirely), which is what
  # AWS Config detects and the remediation Lambda reverts.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
