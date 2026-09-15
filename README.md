# Secure CI/CD Pipeline for a Containerized App on AWS (LocalStack)

A cloud security / DevSecOps portfolio project: a small Spring Boot app deployed
through a security-gated CI/CD pipeline onto AWS infrastructure defined as code.
Built primarily against [LocalStack](https://www.localstack.io/) to keep cost at
effectively $0, with a short, clearly-labeled window on real AWS for the two or
three managed services (GuardDuty, Security Hub) that LocalStack's free tier
doesn't emulate.

## Why this exists

A working repo that backs up a resume, rather than a resume that just claims
these skills. Every module here maps to a real control: least-privilege IAM,
policy-as-code, container hardening, SAST/DAST gates, and automated remediation.

## Repo layout

```
infra/
  bootstrap/          # one-time: creates the Terraform state bucket + lock table
  environments/dev/   # the actual project infrastructure (IAM, VPC, etc.)
app/                  # Spring Boot REST API (added Day 3)
policies/             # OPA / Kyverno admission policies (added Day 5)
scripts/              # setup and helper scripts
docs/
  findings/           # screenshots of scan/posture results
  threat-model.md     # STRIDE pass (added Day 7)
  architecture.png    # architecture diagram (added Day 7)
```

## Day 1 — Environment Setup

**Prerequisites:** Docker, Terraform >= 1.5, AWS CLI, git.

1. Start LocalStack and bootstrap Terraform state in one step:

   ```bash
   ./scripts/setup-day1.sh
   ```

   This does three things:
   - Brings up LocalStack (`docker-compose up -d`), emulating S3, DynamoDB,
     IAM, STS, EC2, Lambda, EventBridge, CloudTrail, and Secrets Manager on
     `localhost:4566`
   - Runs `infra/bootstrap` (using a local Terraform backend) to create the
     `secure-pipeline-tf-state` S3 bucket and `secure-pipeline-tf-lock`
     DynamoDB table *inside* LocalStack
   - Switches to `infra/environments/dev`, points it at that new remote
     backend, and applies a one-resource smoke test to confirm the whole
     chain works end to end

2. Verify manually if you want to see it yourself:

   ```bash
   curl http://localhost:4566/_localstack/health
   aws --endpoint-url=http://localhost:4566 s3 ls
   ```

   You should see `secure-pipeline-tf-state` and `secure-pipeline-day1-smoke-test`
   listed.

**What "done" looks like for Day 1:** LocalStack running, Terraform remote
state backend live inside it, and a successful `terraform apply` against
that backend. Nothing here touches real AWS or costs anything.

## Roadmap

| Day | Focus |
|---|---|
| 1 | Workspace scaffolding + LocalStack setup *(this stage)* |
| 2 | IAM least-privilege roles, permission boundaries, SCPs, CloudTrail, Config |
| 3 | VPC segmentation, Spring Boot app with seeded OWASP Top 10 flaws, hardened Dockerfile |
| 4 | Secrets Manager, Prowler/Checkov posture scans |
| 5 | GitHub Actions pipeline: Checkov, SonarQube, Snyk, Trivy quality gates; Kyverno/OPA admission policy |
| 6 | Boto3 remediation script, full pipeline run |
| 7 | Threat model, architecture diagram, README polish, GitHub launch |

## A note on scope

This project runs almost entirely on LocalStack's free Community tier.
AWS Config, GuardDuty, and Security Hub are AWS-managed services that
LocalStack's free tier doesn't emulate — where this project references them,
the README says explicitly whether that stage ran against real AWS
(budget-capped) or was substituted with an open-source equivalent
(Checkov, Prowler). No skill in this repo is claimed without something
concrete backing it.
