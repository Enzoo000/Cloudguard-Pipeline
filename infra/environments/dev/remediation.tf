# infra/environments/dev/remediation.tf
#
# The response half of Phase 5. AWS Config (audit.tf) detects that the
# reports bucket has drifted to a public posture and emits a compliance
# change event; EventBridge routes that event to a Lambda, which calls
# the S3 API to put the bucket back. Detection and response are
# deliberately separate: Config only ever observes, the Lambda only ever
# acts.

# --- Lambda execution role ---

resource "aws_iam_role" "remediation" {
  name = "northbound-remediation-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Project = "cloudguard-pipeline"
  }
}

# Deliberately narrow, and a direct contrast to the app role in iam.tf,
# which is still wildcard on purpose so the scan gates keep catching it.
# This role can write its own logs and fix the public access block on
# exactly one bucket — nothing else.
resource "aws_iam_role_policy" "remediation" {
  name = "northbound-remediation-policy"
  role = aws_iam_role.remediation.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:*:*:*"
      },
      {
        Effect = "Allow"
        Action = [
          "s3:PutBucketPublicAccessBlock",
          "s3:GetBucketPublicAccessBlock",
        ]
        Resource = aws_s3_bucket.reports.arn
      },
    ]
  })
}

# --- Lambda package ---

data "archive_file" "remediation" {
  type        = "zip"
  source_file = "${path.module}/../../../remediation/lambda/handler.py"
  output_path = "${path.module}/.terraform/remediation-lambda.zip"
}

resource "aws_lambda_function" "remediation" {
  function_name    = "northbound-remediation"
  role             = aws_iam_role.remediation.arn
  handler          = "handler.lambda_handler"
  runtime          = "python3.12"
  filename         = data.archive_file.remediation.output_path
  source_code_hash = data.archive_file.remediation.output_base64sha256
  timeout          = 30

  # No endpoint override here on purpose: LocalStack injects
  # LOCALSTACK_HOSTNAME into the container with the address that's
  # actually reachable from inside it, and handler.py builds its S3
  # endpoint from that. An explicit AWS_ENDPOINT_URL pointing at
  # localhost or localhost.localstack.cloud was tried first and made the
  # function hang until timeout — neither resolves from in there.

  tags = {
    Project = "cloudguard-pipeline"
  }
}

# --- EventBridge: Config compliance change -> Lambda ---

resource "aws_cloudwatch_event_rule" "config_noncompliant" {
  name        = "northbound-config-noncompliance"
  description = "Routes AWS Config NON_COMPLIANT findings to the remediation Lambda"

  event_pattern = jsonencode({
    source        = ["aws.config"]
    "detail-type" = ["Config Rules Compliance Change"]
    detail = {
      newEvaluationResult = {
        complianceType = ["NON_COMPLIANT"]
      }
    }
  })

  tags = {
    Project = "cloudguard-pipeline"
  }
}

resource "aws_cloudwatch_event_target" "remediation" {
  rule      = aws_cloudwatch_event_rule.config_noncompliant.name
  target_id = "northbound-remediation-lambda"
  arn       = aws_lambda_function.remediation.arn
}

resource "aws_lambda_permission" "allow_eventbridge" {
  statement_id  = "AllowExecutionFromEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.remediation.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.config_noncompliant.arn
}
