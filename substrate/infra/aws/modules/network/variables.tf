variable "name_prefix" {
  description = "meridian-<engagement_id> prefix applied to every resource name."
  type        = string
}

variable "engagement_id" {
  description = "Engagement identifier, used in log-group paths (/meridian/<engagement>/...)."
  type        = string
}

variable "aws_region" {
  description = "Deployment region — used to build VPC interface endpoint service names."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the engagement VPC. Subnet carving uses cidrsubnet(vpc_cidr, 8, n): a /16 yields /24 subnets."
  type        = string
}

variable "az_count" {
  description = "Number of availability zones to create subnet tiers in."
  type        = number
}

variable "alb_internal" {
  description = "Internal-ALB posture. When false (public ALB), the ALB security group also admits port 80 for the HTTPS redirect listener."
  type        = bool
}

variable "alb_allowed_ingress_cidrs" {
  description = "CIDRs allowed to reach the ALB security group."
  type        = list(string)
}

variable "enable_nat_gateway" {
  description = "Provision NAT gateway(s) for private-app-subnet egress (Anthropic API path)."
  type        = bool
}

variable "nat_gateway_per_az" {
  description = "One NAT gateway per AZ instead of a single shared one."
  type        = bool
}

variable "enable_vpc_endpoints" {
  description = "Provision interface endpoints (ecr.api, ecr.dkr, logs, secretsmanager, kms) and the S3 gateway endpoint."
  type        = bool
}

variable "enable_ssm_endpoints" {
  description = "Additionally provision the ssmmessages interface endpoint (required for ECS Exec in private subnets)."
  type        = bool
}

variable "kms_key_arn" {
  description = "Engagement CMK ARN used to encrypt the VPC flow-log CloudWatch log group."
  type        = string
}

variable "log_retention_days" {
  description = "Retention for the VPC flow-log log group."
  type        = number
}
