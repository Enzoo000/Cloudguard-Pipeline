# infra/environments/dev/vpc.tf
#
# Real network segmentation for Northbound Analytics: a VPC split into
# public and private subnets across two AZs, each with its own route
# table. Public subnets route out through an Internet Gateway; private
# subnets have no route to the internet at all (no NAT gateway — see
# note below), which is the actual mechanism "segmentation" means here,
# not just a label.
#
# Nothing is placed in these subnets yet — that happens once Phase 4/5
# add real compute (ECS/EC2/Lambda-in-VPC). This is the network layer
# those phases will attach to.

resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name    = "northbound-vpc"
    Project = "cloudguard-pipeline"
    Target  = "northbound-analytics"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name    = "northbound-igw"
    Project = "cloudguard-pipeline"
  }
}

# --- Public subnets (route to the internet via the IGW) ---

resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "us-east-1a"
  map_public_ip_on_launch = true

  tags = {
    Name    = "northbound-public-a"
    Tier    = "public"
    Project = "cloudguard-pipeline"
  }
}

resource "aws_subnet" "public_b" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "us-east-1b"
  map_public_ip_on_launch = true

  tags = {
    Name    = "northbound-public-b"
    Tier    = "public"
    Project = "cloudguard-pipeline"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name    = "northbound-public-rt"
    Project = "cloudguard-pipeline"
  }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_b" {
  subnet_id      = aws_subnet.public_b.id
  route_table_id = aws_route_table.public.id
}

# --- Private subnets (no internet route — this is the segmentation) ---

resource "aws_subnet" "private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.11.0/24"
  availability_zone = "us-east-1a"

  tags = {
    Name    = "northbound-private-a"
    Tier    = "private"
    Project = "cloudguard-pipeline"
  }
}

resource "aws_subnet" "private_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.12.0/24"
  availability_zone = "us-east-1b"

  tags = {
    Name    = "northbound-private-b"
    Tier    = "private"
    Project = "cloudguard-pipeline"
  }
}

# No route to the IGW (or a NAT gateway) is defined here on purpose —
# this route table only carries the implicit local (10.0.0.0/16) route
# Terraform/AWS adds automatically, so resources placed in these subnets
# cannot be reached from, or reach out to, the public internet directly.
#
# A NAT gateway would let private-subnet resources make outbound calls
# (e.g. to pull a package) while staying unreachable from the internet.
# Deliberately left out for now to keep this environment's cost/surface
# minimal — add aws_nat_gateway + a private default route here if a
# later phase's workload needs outbound internet access.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name    = "northbound-private-rt"
    Project = "cloudguard-pipeline"
  }
}

resource "aws_route_table_association" "private_a" {
  subnet_id      = aws_subnet.private_a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_b" {
  subnet_id      = aws_subnet.private_b.id
  route_table_id = aws_route_table.private.id
}
