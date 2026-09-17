# infra/environments/dev/main.tf
#
# Northbound Analytics' target infrastructure lives in this directory,
# split by resource type: s3.tf, iam.tf, network.tf. See each file's
# header comment for the specific misconfiguration seeded into it — those
# are what the pre-deploy scan gate (Checkov) is meant to catch.
#
# The Day 1 smoke-test bucket (cloudguard-day1-smoke-test) that used to
# live here has been replaced by this real infrastructure; Day 1's
# verification already proved the remote state backend works end to end.
