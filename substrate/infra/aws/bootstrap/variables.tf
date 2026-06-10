variable "engagement_id" {
  description = "Short engagement identifier — must match the value used for the main stack (drives the bucket name and KMS alias)."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{2,19}$", var.engagement_id))
    error_message = "engagement_id must match ^[a-z0-9][a-z0-9-]{2,19}$ (lowercase, 3-20 chars, starts alphanumeric)."
  }
}

variable "aws_region" {
  description = "Customer-agreed AWS region — must match the main stack."
  type        = string
}

variable "deploy_role_arn" {
  description = "ARN of the meridian-<engagement>-deploy role created by customer-handoff/meridian-deploy-role.yaml."
  type        = string
}

variable "external_id" {
  description = "sts:ExternalId for the deploy role trust policy. Supply via TF_VAR_external_id — never commit."
  type        = string
  sensitive   = true
}
