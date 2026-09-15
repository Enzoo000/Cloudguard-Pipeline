#!/usr/bin/env bash
# scripts/setup-day1.sh
#
# Day 1: start LocalStack, bootstrap the Terraform remote state backend,
# then verify the dev environment can init/apply against it.

set -euo pipefail

echo "== Starting LocalStack =="
docker-compose up -d

echo "== Waiting for LocalStack to report healthy =="
until curl -s http://localhost:4566/_localstack/health 2>/dev/null | grep -q '"s3": "available"'; do
  echo "  ...still waiting on LocalStack"
  sleep 2
done
echo "LocalStack is up."

echo "== Bootstrapping Terraform state backend (S3 + DynamoDB) =="
pushd infra/bootstrap > /dev/null
terraform init
terraform apply -auto-approve
popd > /dev/null

echo "== Verifying dev environment can use the remote backend =="
pushd infra/environments/dev > /dev/null
terraform init
terraform apply -auto-approve
popd > /dev/null

echo "== Day 1 complete: LocalStack running, remote state backend live, smoke test applied =="
