# infra/environments/dev/s3.tf
#
# Storage for Northbound Analytics' report exports (the target app's
# GET /exports/{filename} feature).
#
# SEEDED FLAW: public_access_block is deliberately permissive and the
# bucket has no encryption or versioning configured. This is the classic
# "public bucket" misconfiguration Checkov is meant to catch in the
# pre-deploy scan gate, and the resource the manual-drift demo (Day 6)
# points at. Day 4 hardens this once the scan gate exists to prove it
# actually caught it.

resource "aws_s3_bucket" "reports" {
  bucket = "northbound-analytics-reports"

  tags = {
    Project = "cloudguard-pipeline"
    Target  = "northbound-analytics"
  }
}

resource "aws_s3_bucket_public_access_block" "reports" {
  bucket = aws_s3_bucket.reports.id

  # SEEDED FLAW: all four should be true. Left false so the bucket can be
  # made public, mirroring the most common real-world S3 breach pattern.
  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}
