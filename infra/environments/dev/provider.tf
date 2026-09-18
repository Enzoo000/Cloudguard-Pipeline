# infra/environments/dev/provider.tf

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    # Zips the remediation Lambda source at plan time — see
    # remediation.tf's archive_file data source.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.0"
    }
  }
}

provider "aws" {
  region                      = "us-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  # All AWS API calls route to LocalStack instead of real AWS.
  #
  # Any service NOT listed here silently goes to real AWS instead — which
  # is exactly what happened when Phase 5's Config and CloudTrail
  # resources were first applied: the calls left LocalStack entirely and
  # real AWS rejected the fake "test" credentials with
  # UnrecognizedClientException. Nothing was created there (the
  # credentials aren't valid), but the failure mode is silent enough to
  # be worth naming: add the endpoint here before using a new service.
  endpoints {
    s3            = "http://localhost:4566"
    dynamodb      = "http://localhost:4566"
    iam           = "http://localhost:4566"
    sts           = "http://localhost:4566"
    ec2           = "http://localhost:4566"
    configservice = "http://localhost:4566"
    cloudtrail    = "http://localhost:4566"
    lambda        = "http://localhost:4566"
    events        = "http://localhost:4566"
    logs          = "http://localhost:4566"
  }
}
