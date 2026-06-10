variable "name_prefix" {
  description = "meridian-<engagement_id> prefix for resource names."
  type        = string
}

variable "db_subnet_ids" {
  description = "Isolated-tier subnet ids for the DB subnet group (no NAT route)."
  type        = list(string)
}

variable "db_security_group_id" {
  description = "Security group admitting Postgres traffic from the app tier only."
  type        = string
}

variable "kms_key_arn" {
  description = "Engagement CMK ARN for storage encryption and Performance Insights."
  type        = string
}

variable "db_name" {
  description = "Initial database name."
  type        = string
}

variable "db_user" {
  description = "Master username."
  type        = string
}

variable "db_master_password" {
  description = "Master password from the secrets module's ephemeral random_password. Ephemeral — consumed only by the write-only password_wo argument; never persisted to state or plan."
  type        = string
  sensitive   = true
  ephemeral   = true
}

variable "db_master_password_version" {
  description = "Rotation counter for password_wo_version. Must match the secrets module's secret_string_wo_version so the secret and the RDS password rotate together."
  type        = number
}

variable "db_engine_version" {
  description = "Postgres engine version (major-only string such as \"16\" lets RDS track the latest minor)."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "db_multi_az" {
  description = "Provision a Multi-AZ standby."
  type        = bool
}

variable "db_allocated_storage_gb" {
  description = "Initial allocated storage (GiB, gp3)."
  type        = number
}

variable "db_max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling (GiB)."
  type        = number
}

variable "db_backup_retention_days" {
  description = "Automated backup retention (days)."
  type        = number
}
