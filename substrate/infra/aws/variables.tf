# ---------------------------------------------------------------------------
# Engagement identity & provider wiring
# ---------------------------------------------------------------------------

variable "engagement_id" {
  description = "Short engagement identifier. Drives every resource name (meridian-<engagement_id>-*), the SUBSTRATE_ENGAGEMENT_ID env var, and tagging. Lowercase alphanumerics and hyphens, 3-20 chars, must start alphanumeric."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{2,19}$", var.engagement_id))
    error_message = "engagement_id must match ^[a-z0-9][a-z0-9-]{2,19}$ (lowercase, 3-20 chars, starts alphanumeric)."
  }
}

variable "aws_region" {
  description = "Customer-agreed AWS region the engagement stack deploys into."
  type        = string
}

variable "deploy_role_arn" {
  description = "ARN of the meridian-<engagement>-deploy IAM role created in the customer account by customer-handoff/meridian-deploy-role.yaml."
  type        = string
}

variable "external_id" {
  description = "sts:ExternalId required by the deploy role trust policy (confused-deputy guard). Supply via TF_VAR_external_id in the operator environment — never commit to tfvars."
  type        = string
  sensitive   = true
}

variable "tags" {
  description = "Additional tags merged into the provider default_tags applied to every resource. Keys with the meridian: prefix are reserved — the deploy role's IAM tag conditions depend on them."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for k in keys(var.tags) : !startswith(k, "meridian:")])
    error_message = "tags may not contain meridian:-prefixed keys (reserved control tags: meridian:engagement, meridian:product, meridian:managed-by)."
  }
}

# ---------------------------------------------------------------------------
# Application image & app-level configuration (maps to the substrate env contract)
# ---------------------------------------------------------------------------

variable "image_uri" {
  description = "Digest-pinned container image URI for substrate-app (e.g. <acct>.dkr.ecr.<region>.amazonaws.com/meridian-<id>-substrate@sha256:...). CI builds and pushes the image; the operator copies the exact digest into the engagement registry (see docs/deploy.md step 4)."
  type        = string

  validation {
    condition     = can(regex("@sha256:[a-f0-9]{64}$", var.image_uri))
    error_message = "image_uri must be digest-pinned (end with @sha256:<64-hex>). Resolve a tag to a digest with `crane digest <uri>`."
  }
}

variable "oidc_issuer_uri" {
  description = "Customer IdP OIDC issuer URI (input only — Meridian never provisions the IdP). Feeds SUBSTRATE_OIDC_ISSUER_URI."
  type        = string
}

variable "auth_mode" {
  description = "Substrate auth mode. Production deploys are OIDC-only; revisit the validation if a sandbox engagement ever needs anonymous mode."
  type        = string
  default     = "oidc"

  validation {
    condition     = contains(["oidc"], var.auth_mode)
    error_message = "auth_mode must be \"oidc\" for customer-account deploys."
  }
}

variable "enable_ai_assist" {
  description = "Gates ONLY the ANTHROPIC_API_KEY container wiring — the secret shell always exists so the operator can write the value first. Start false; flip true only after the operator has written the key with put-secret-value (two-phase flow in docs/deploy.md step 6 — ECS fails task launch on a value-less secret)."
  type        = bool
  default     = false
}

variable "llm_model" {
  description = "Value for SUBSTRATE_LLM_MODEL. Empty string omits the env var so the application default applies."
  type        = string
  default     = ""
}

variable "orchestrator_enabled" {
  description = "Whether the orchestrator sibling is deployed for this engagement (whole-or-in-part: substrate-only engagements leave this false). Feeds SUBSTRATE_ORCHESTRATOR_ENABLED."
  type        = bool
  default     = false
}

variable "orchestrator_base_url" {
  description = "Base URL of the engagement's orchestrator deployment. Empty string omits SUBSTRATE_ORCHESTRATOR_URL."
  type        = string
  default     = ""
}

variable "cors_allowed_origins" {
  description = "Browser origins allowed by the app (SUBSTRATE_CORS_ALLOWEDORIGINS). Empty list falls back to https://<app_fqdn> when app_fqdn is set; when both are empty the env var is omitted and the operator sets this to the ALB DNS name after the first apply (see docs/deploy.md)."
  type        = list(string)
  default     = []
}

variable "app_fqdn" {
  description = "Customer-owned DNS name for the app (never provisioned by this stack). Used for the CORS default, app_url output, and the OIDC redirect-URI hint."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "db_name" {
  description = "Postgres database name (SUBSTRATE_DB_NAME)."
  type        = string
  default     = "substrate"
}

variable "db_user" {
  description = "Postgres master username (SUBSTRATE_DB_USER)."
  type        = string
  default     = "substrate"
}

variable "db_instance_class" {
  description = "RDS instance class for the engagement database."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_multi_az" {
  description = "Multi-AZ standby for RDS. Default false — engagement dashboards have a modest RTO; flip per contract."
  type        = bool
  default     = false
}

variable "db_allocated_storage_gb" {
  description = "Initial RDS storage allocation in GiB (gp3)."
  type        = number
  default     = 50
}

variable "db_max_allocated_storage_gb" {
  description = "Storage-autoscaling ceiling in GiB."
  type        = number
  default     = 200
}

variable "db_backup_retention_days" {
  description = "Automated RDS backup retention in days (1-35)."
  type        = number
  default     = 14

  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days must be between 1 and 35."
  }
}

variable "db_engine_version" {
  description = "Postgres engine version. Major-only (\"16\", matching the compose postgres:16 contract) lets RDS pick the latest minor under auto_minor_version_upgrade."
  type        = string
  default     = "16"
}

variable "db_password_rotation_counter" {
  description = "Monotonic counter driving password_wo_version / secret_string_wo_version. Bump by 1 to rotate the DB master password (rotation procedure in docs/deploy.md Day-2)."
  type        = number
  default     = 1
}

variable "db_connections_alarm_threshold" {
  description = "DatabaseConnections alarm threshold — set to ~80% of the instance class connection ceiling (db.t4g.medium: LEAST(DBInstanceClassMemory/9531392, 5000) ≈ 450, so 360). Adjust when changing db_instance_class."
  type        = number
  default     = 360
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block for the per-engagement VPC. Must be agreed with the customer so it does not overlap their network (VPN/peering reachability for internal ALBs)."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr))
    error_message = "vpc_cidr must be a valid IPv4 CIDR block."
  }
}

variable "az_count" {
  description = "Number of availability zones to spread subnets across."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count must be 2 or 3."
  }
}

variable "alb_internal" {
  description = "true = internal ALB reached over the customer's VPN/DX/peering (default posture); false = internet-facing ALB in public subnets with an ACM public certificate."
  type        = bool
  default     = true
}

variable "alb_allowed_ingress_cidrs" {
  description = "CIDRs allowed to reach the ALB on 443 (and 80 in public mode). Required — there is deliberately no default-open."
  type        = list(string)

  validation {
    condition     = length(var.alb_allowed_ingress_cidrs) > 0
    error_message = "alb_allowed_ingress_cidrs must contain at least one CIDR."
  }
}

variable "enable_nat_gateway" {
  description = "Provision NAT for private-subnet egress. Required only for the Anthropic API call path — AWS control-plane traffic uses VPC endpoints. Safe to disable when AI assist is off for the engagement."
  type        = bool
  default     = true
}

variable "nat_gateway_per_az" {
  description = "true = one NAT gateway per AZ (availability); false = single NAT gateway (cost/burden default)."
  type        = bool
  default     = false
}

variable "enable_vpc_endpoints" {
  description = "Provision interface endpoints (ECR api/dkr, Logs, Secrets Manager, KMS, plus ssmmessages when ECS Exec is enabled) and the S3 gateway endpoint, keeping AWS control-plane traffic off the internet."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# Service / compute
# ---------------------------------------------------------------------------

variable "certificate_arn" {
  description = "ACM certificate ARN for the ALB 443 listener (public cert for internet-facing, imported/private-CA cert for internal)."
  type        = string
}

variable "task_cpu" {
  description = "Fargate task CPU units (must be a valid Fargate cpu/memory combination)."
  type        = number
  default     = 1024
}

variable "task_memory" {
  description = "Fargate task memory in MiB (must be a valid Fargate cpu/memory combination)."
  type        = number
  default     = 2048
}

variable "desired_count" {
  description = "Number of substrate-app tasks."
  type        = number
  default     = 1

  validation {
    condition     = var.desired_count >= 1
    error_message = "desired_count must be at least 1."
  }
}

variable "cpu_architecture" {
  description = "Fargate CPU architecture. MUST match the architecture of the CI-built image (X86_64, or ARM64 for Graviton)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "enable_persistent_storage" {
  description = "Mount EFS (encrypted, TLS, uid-1001 access points) at the app's data/uploads/staging paths. Fargate storage is ephemeral — disable only if losing uploads on every deploy is acceptable. Revisit when uploads move to S3."
  type        = bool
  default     = true
}

variable "enable_execute_command" {
  description = "Enable ECS Exec for operator debugging shells. Least-privilege default off."
  type        = bool
  default     = false
}

variable "create_ecr_repo" {
  description = "Create the per-engagement ECR repository in the customer account. Set false when the customer supplies their own registry (point image_uri at it)."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# Observability
# ---------------------------------------------------------------------------

variable "log_retention_days" {
  description = "CloudWatch Logs retention for the app and VPC flow-log groups. Must be a value CloudWatch accepts."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the CloudWatch-supported retention values."
  }
}

variable "alarm_email" {
  description = "Email endpoint subscribed to the engagement alarm SNS topic. Empty string creates the topic with no subscription."
  type        = string
  default     = ""
}

variable "enable_alb_access_logs" {
  description = "Ship ALB access logs to a per-engagement S3 bucket (SSE-S3 — AWS does not support CMK for ALB log delivery)."
  type        = bool
  default     = true
}
