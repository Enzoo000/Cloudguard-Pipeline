package main

# Denies a planned aws_iam_role_policy whose policy document grants a
# wildcard Action or Resource. Mirrors Checkov's CKV2_AWS_40. The policy
# document is stored as a JSON-encoded string in the plan (from
# jsonencode() in iam.tf), so it has to be parsed before inspection.

deny contains msg if {
	rc := input.resource_changes[_]
	rc.type == "aws_iam_role_policy"
	after := rc.change.after
	policy := json.unmarshal(after.policy)
	stmt := policy.Statement[_]
	stmt.Action == "*"
	msg := sprintf("%v: IAM policy statement grants wildcard Action \"*\"", [rc.address])
}

deny contains msg if {
	rc := input.resource_changes[_]
	rc.type == "aws_iam_role_policy"
	after := rc.change.after
	policy := json.unmarshal(after.policy)
	stmt := policy.Statement[_]
	stmt.Resource == "*"
	msg := sprintf("%v: IAM policy statement grants wildcard Resource \"*\"", [rc.address])
}
