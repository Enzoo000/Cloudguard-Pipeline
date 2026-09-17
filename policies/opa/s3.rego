package main

# Denies a planned aws_s3_bucket_public_access_block resource that leaves
# any of the four public-access flags disabled. Mirrors Checkov's
# CKV2_AWS_6, but runs against the actual terraform plan instead of the
# raw source — catches this even if the flaw only shows up after
# variables/interpolation are resolved.

s3_public_access_flags := [
	"block_public_acls",
	"block_public_policy",
	"ignore_public_acls",
	"restrict_public_buckets",
]

deny contains msg if {
	rc := input.resource_changes[_]
	rc.type == "aws_s3_bucket_public_access_block"
	after := rc.change.after
	flag := s3_public_access_flags[_]
	after[flag] == false
	msg := sprintf("%v: %v must be true, found false (public access block disabled)", [rc.address, flag])
}
