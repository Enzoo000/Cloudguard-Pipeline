# infra/environments/dev/network.tf
#
# Security group for the Northbound Analytics application instance, now
# scoped to the real VPC defined in vpc.tf instead of LocalStack's
# default VPC.
#
# SEEDED FLAW: ingress is still open to 0.0.0.0/0 on the app port
# instead of being scoped to a load balancer or a known CIDR range. This
# is deliberately left in place — it's the misconfiguration Checkov is
# meant to catch in Phase 3, and fixing it now would remove the thing
# the scan gate is supposed to demonstrate.

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
