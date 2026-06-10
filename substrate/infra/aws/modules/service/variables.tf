variable "name_prefix" {
  description = "meridian-<engagement_id> prefix for resource names."
  type        = string
}

variable "aws_region" {
  description = "Deployment region — used by the awslogs log configuration."
  type        = string
}

variable "vpc_id" {
  description = "Engagement VPC id."
  type        = string
}

variable "app_subnet_ids" {
  description = "Private app-tier subnet ids for Fargate tasks and EFS mount targets."
  type        = list(string)
}

variable "alb_subnet_ids" {
  description = "Subnets for the ALB: public tier when internet-facing, private app tier when internal."
  type        = list(string)
}

variable "alb_internal" {
  description = "Whether the ALB is internal. Public mode adds the port-80 redirect listener."
  type        = bool
}

variable "alb_name" {
  description = "ALB name, pre-truncated to the 32-char AWS limit by the root module."
  type        = string
}

variable "target_group_name" {
  description = "Target group name, pre-truncated to the 32-char AWS limit by the root module."
  type        = string
}

variable "alb_security_group_id" {
  description = "Security group for the ALB."
  type        = string
}

variable "app_security_group_id" {
  description = "Security group for the Fargate tasks."
  type        = string
}

variable "efs_security_group_id" {
  description = "Security group for EFS mount targets."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate ARN for the 443 listener."
  type        = string
}

variable "image_uri" {
  description = "Digest-pinned container image URI for substrate-app."
  type        = string
}

variable "task_cpu" {
  description = "Fargate task CPU units."
  type        = number
}

variable "task_memory" {
  description = "Fargate task memory (MiB)."
  type        = number
}

variable "cpu_architecture" {
  description = "Fargate CPU architecture (X86_64 or ARM64) — must match the image."
  type        = string
}

variable "desired_count" {
  description = "Number of substrate-app tasks."
  type        = number
}

variable "container_environment" {
  description = "Plain environment variables for the container (assembled in root from the substrate env contract). Never contains secrets."
  type        = map(string)
}

variable "container_secrets" {
  description = "Map of env-var name -> Secrets Manager secret ARN, injected via the ECS `secrets` channel at task launch."
  type        = map(string)
}

variable "kms_key_arn" {
  description = "Engagement CMK ARN — EFS encryption and the execution role's kms:Decrypt scope."
  type        = string
}

variable "log_group_name" {
  description = "Pre-existing CloudWatch log group (created by the observability module) the awslogs driver ships to."
  type        = string
}

variable "enable_persistent_storage" {
  description = "Provision EFS (uid-1001 access points, TLS mounts) for the app's data/uploads/staging directories."
  type        = bool
}

variable "create_ecr_repo" {
  description = "Create the per-engagement ECR repository."
  type        = bool
}

variable "enable_execute_command" {
  description = "Enable ECS Exec on the service and grant the task role the ssmmessages channel actions."
  type        = bool
}

variable "enable_alb_access_logs" {
  description = "Ship ALB access logs to the observability module's S3 bucket."
  type        = bool
}

variable "access_logs_bucket" {
  description = "Name of the ALB access-log bucket (null when access logs are disabled)."
  type        = string
  nullable    = true
  default     = null
}

variable "health_check_grace_period_seconds" {
  description = "ALB health-check grace period for new tasks — Spring Boot start plus Flyway migrations."
  type        = number
  default     = 180
}
