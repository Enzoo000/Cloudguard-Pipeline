# infra/environments/dev/network.tf
#
# Security group for the Northbound Analytics application instance, now
# scoped to the real VPC defined in vpc.tf instead of LocalStack's
# default VPC.
#
# SEEDED FLAW: ingress is open to 0.0.0.0/0 on both the app port and SSH
# instead of being scoped to a load balancer or a known CIDR range. This
# is deliberately left in place — it's the misconfiguration Checkov is
# meant to catch in Phase 3, and fixing it now would remove the thing
# the scan gate is supposed to demonstrate.
#
# Port 22 was added specifically because Checkov's built-in ingress
# checks are keyed to well-known sensitive ports (CKV_AWS_24 for
# unrestricted SSH, CKV_AWS_260 for port 80) rather than arbitrary
# application ports — 8080 alone didn't trigger a dedicated finding when
# this ran for real in Phase 3 (confirmed via GitHub Actions, not just
# assumed). This port 22 rule exists purely to make that check fire; the
# app itself has no SSH service and never will.

resource "aws_security_group" "app" {
  name        = "northbound-app-sg"
  description = "Security group for the Northbound Analytics app instance"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "App traffic"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    # SEEDED FLAW: should be restricted, not open to the entire internet.
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    # SEEDED FLAW: unrestricted SSH — triggers Checkov's CKV_AWS_24.
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Project = "cloudguard-pipeline"
    Target  = "northbound-analytics"
  }
}
