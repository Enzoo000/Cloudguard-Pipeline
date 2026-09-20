package main

# Denies a planned aws_security_group with any ingress rule open to
# 0.0.0.0/0, on any port. Deliberately broader than Checkov's approach
# here: Checkov's built-in checks (e.g. CKV_AWS_24) are keyed to specific
# well-known ports, which is why our port-8080 rule never got its own
# Checkov finding in Phase 3. This policy checks the CIDR itself, not a
# fixed port list, so it catches both the port-8080 and port-22 rules.
#
# The port is part of the ruleId so two open ports on the same security
# group stay two distinct findings rather than collapsing into one when
# the dashboard fingerprints them.

deny contains msg if {
	rc := input.resource_changes[_]
	rc.type == "aws_security_group"
	after := rc.change.after
	ingress := after.ingress[_]
	cidr := ingress.cidr_blocks[_]
	cidr == "0.0.0.0/0"
	msg := {
		"msg": sprintf("%v: ingress on port %v is open to 0.0.0.0/0", [rc.address, ingress.from_port]),
		"ruleId": sprintf("OPA_SG_OPEN_INGRESS_%v", [ingress.from_port]),
		"resource": rc.address,
		"severity": "HIGH",
	}
}
