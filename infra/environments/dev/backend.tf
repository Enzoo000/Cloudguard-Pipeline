# infra/environments/dev/backend.tf
#
# Remote state backend, pointing at the S3 bucket + DynamoDB table created
# by infra/bootstrap on Day 1. Run `terraform init` here only AFTER the
# bootstrap apply has succeeded.

terraform {
  backend "s3" {
    bucket         = "cloudguard-tf-state"
    key            = "dev/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "cloudguard-tf-lock"

    access_key                  = "test"
    secret_key                  = "test"
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_requesting_account_id  = true
    use_path_style              = true

    endpoints = {
      s3       = "http://localhost:4566"
      dynamodb = "http://localhost:4566"
    }
  }
}

# NOTE: backend block syntax for endpoints/use_path_style changed between
# Terraform versions. If `terraform init` complains about unsupported
# arguments in this block, you're likely on an older AWS provider/Terraform
# combo — swap `use_path_style` for `force_path_style` and/or move the
# `endpoints` map back to individual `endpoint = { s3 = ..., dynamodb = ... }`
# style keys per the version installed. This is a known rough edge with
# LocalStack + S3 backends, not a mistake in your config.
