# Per-engagement VPC: three subnet tiers per AZ (public / private-app /
# isolated-db), NAT only for the Anthropic egress path, interface endpoints for
# AWS control-plane traffic, and ALL security groups for the stack (created
# here, referenced by database/service, so there are no inter-module SG cycles).

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  nat_count = var.enable_nat_gateway ? (var.nat_gateway_per_az ? var.az_count : 1) : 0

  interface_endpoint_services = var.enable_vpc_endpoints ? concat(
    ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "kms"],
    var.enable_ssm_endpoints ? ["ssmmessages"] : [],
  ) : []
}

# ---------------------------------------------------------------------------
# VPC + subnets
# ---------------------------------------------------------------------------

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name_prefix}-vpc" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-igw" }
}

# Tier offsets keep the carving readable: public = 0+, app = 10+, db = 20+.
resource "aws_subnet" "public" {
  count = var.az_count

  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = false

  tags = { Name = "${var.name_prefix}-public-${local.azs[count.index]}" }
}

resource "aws_subnet" "private_app" {
  count = var.az_count

  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 10 + count.index)

  tags = { Name = "${var.name_prefix}-app-${local.azs[count.index]}" }
}

resource "aws_subnet" "isolated_db" {
  count = var.az_count

  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 20 + count.index)

  tags = { Name = "${var.name_prefix}-db-${local.azs[count.index]}" }
}

# ---------------------------------------------------------------------------
# Routing: public -> IGW; app -> NAT (when enabled); db -> no default route.
# ---------------------------------------------------------------------------

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-rt-public" }
}

resource "aws_route" "public_default" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = var.az_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count = local.nat_count

  domain = "vpc"

  tags = { Name = "${var.name_prefix}-nat-eip-${count.index}" }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count = local.nat_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = { Name = "${var.name_prefix}-nat-${count.index}" }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "private_app" {
  count = var.az_count

  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-rt-app-${local.azs[count.index]}" }
}

resource "aws_route" "app_default" {
  count = var.enable_nat_gateway ? var.az_count : 0

  route_table_id         = aws_route_table.private_app[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.nat_gateway_per_az ? count.index : 0].id
}

resource "aws_route_table_association" "private_app" {
  count = var.az_count

  subnet_id      = aws_subnet.private_app[count.index].id
  route_table_id = aws_route_table.private_app[count.index].id
}

# Isolated tier: a route table with only the implicit local route — no NAT, no IGW.
resource "aws_route_table" "isolated_db" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-rt-db" }
}

resource "aws_route_table_association" "isolated_db" {
  count = var.az_count

  subnet_id      = aws_subnet.isolated_db[count.index].id
  route_table_id = aws_route_table.isolated_db.id
}

# ---------------------------------------------------------------------------
# Security groups — the full set for the stack lives here (design §C):
#   alb_sg  : 443 from allowed CIDRs (plus 80 in public mode) -> app 8080
#   app_sg  : 8080 from alb_sg; scoped egress (443 out, 5432 -> db, 2049 -> efs)
#   db_sg   : 5432 from app_sg only; no egress
#   efs_sg  : 2049 from app_sg only
#   vpce_sg : 443 from the VPC CIDR (interface endpoints)
# ---------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Engagement ALB: HTTPS from approved CIDRs only"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = toset(var.alb_allowed_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from approved CIDR"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# Port 80 exists only in public mode, solely for the 301 redirect listener.
resource "aws_vpc_security_group_ingress_rule" "alb_http_redirect" {
  for_each = var.alb_internal ? toset([]) : toset(var.alb_allowed_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "HTTP redirect-to-HTTPS from approved CIDR (public ALB only)"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forwarding + health checks to substrate-app tasks"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-app"
  description = "substrate-app Fargate tasks"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-app" }
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "App traffic from the engagement ALB"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Scoped egress instead of all-protocols/all-destinations: 443 out (Anthropic
# API via NAT + AWS interface/S3 endpoints), 5432 to the db SG, 2049 to the efs
# SG. Defense-in-depth for the data-residency posture, NOT a full exfil control
# (exfiltration over 443 is unaffected) — an allowlisting forward proxy is the
# stronger control if an engagement contract demands it. DNS to the VPC
# resolver (.2) is not evaluated by security groups, so it needs no rule.
resource "aws_vpc_security_group_egress_rule" "app_https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS egress (Anthropic API via NAT, AWS interface/S3 endpoints)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "app_to_db" {
  security_group_id            = aws_security_group.app.id
  description                  = "Postgres to the engagement database"
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "app_to_efs" {
  security_group_id            = aws_security_group.app.id
  description                  = "NFS over TLS to the engagement EFS mount targets"
  referenced_security_group_id = aws_security_group.efs.id
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db"
  description = "Engagement RDS Postgres — app tier only"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-db" }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "Postgres from substrate-app tasks"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_security_group" "efs" {
  name        = "${var.name_prefix}-efs"
  description = "Engagement EFS mount targets — app tier only"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-efs" }
}

resource "aws_vpc_security_group_ingress_rule" "efs_from_app" {
  security_group_id            = aws_security_group.efs.id
  description                  = "NFS/TLS from substrate-app tasks"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
}

resource "aws_security_group" "vpce" {
  name        = "${var.name_prefix}-vpce"
  description = "VPC interface endpoints — HTTPS from inside the VPC"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-vpce" }
}

resource "aws_vpc_security_group_ingress_rule" "vpce_https" {
  security_group_id = aws_security_group.vpce.id
  description       = "HTTPS from the engagement VPC"
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# ---------------------------------------------------------------------------
# VPC endpoints — AWS control-plane traffic stays off the internet; NAT exists
# only for the Anthropic API. S3 gateway is required for ECR image-layer pulls.
# ---------------------------------------------------------------------------

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(local.interface_endpoint_services)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private_app[*].id
  security_group_ids  = [aws_security_group.vpce.id]
  private_dns_enabled = true

  tags = { Name = "${var.name_prefix}-vpce-${replace(each.value, ".", "-")}" }
}

resource "aws_vpc_endpoint" "s3_gateway" {
  count = var.enable_vpc_endpoints ? 1 : 0

  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private_app[*].id

  tags = { Name = "${var.name_prefix}-vpce-s3" }
}

# ---------------------------------------------------------------------------
# VPC flow logs -> CloudWatch (engagement CMK)
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/meridian/${var.engagement_id}/vpc-flow-logs"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

resource "aws_iam_role" "flow_logs" {
  name = "${var.name_prefix}-vpc-flow-logs"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "vpc-flow-logs.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy" "flow_logs" {
  name = "flow-log-delivery"
  role = aws_iam_role.flow_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DeliverFlowLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogGroups",
          "logs:DescribeLogStreams"
        ]
        Resource = "${aws_cloudwatch_log_group.flow_logs.arn}:*"
      }
    ]
  })
}

resource "aws_flow_log" "vpc" {
  vpc_id               = aws_vpc.this.id
  traffic_type         = "ALL"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn         = aws_iam_role.flow_logs.arn

  tags = { Name = "${var.name_prefix}-flow-logs" }
}
