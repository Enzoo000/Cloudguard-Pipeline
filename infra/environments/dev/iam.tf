# infra/environments/dev/iam.tf
#
# The role Northbound Analytics' application assumes at runtime.
#
# SEEDED FLAW: the attached policy grants "*" on "*" instead of the
# specific actions the app actually needs (read/write its own S3 bucket,
# read its own Secrets Manager entry). This is a textbook
# privilege-escalation risk and the second misconfiguration Checkov is
# meant to catch. Day 2 replaces it with a scoped, least-privilege policy.

resource "aws_iam_role" "app" {
  name = "northbound-app-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Project = "cloudguard-pipeline"
    Target  = "northbound-analytics"
  }
}

resource "aws_iam_role_policy" "app" {
  name = "northbound-app-policy"
  role = aws_iam_role.app.id

  # SEEDED FLAW: wildcard action + wildcard resource. Should be scoped to
  # s3:GetObject/PutObject on the reports bucket and
  # secretsmanager:GetSecretValue on the app's own secret.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "*"
      Resource = "*"
    }]
  })
}
