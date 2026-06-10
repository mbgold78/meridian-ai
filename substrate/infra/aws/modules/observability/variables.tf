variable "name_prefix" {
  description = "meridian-<engagement_id> prefix for resource names."
  type        = string
}

variable "engagement_id" {
  description = "Engagement identifier — drives the log-group path and the access-log bucket name."
  type        = string
}

variable "aws_region" {
  description = "Deployment region — used for the dashboard and the ELB log-delivery account lookup."
  type        = string
}

variable "kms_key_arn" {
  description = "Engagement CMK ARN for the app log group. (ALB access logs are SSE-S3 — AWS does not support CMK there.)"
  type        = string
}

variable "log_retention_days" {
  description = "Retention for the app log group."
  type        = number
}

variable "alarm_email" {
  description = "Email subscribed to the alarm topic; empty string skips the subscription."
  type        = string
}

variable "enable_alb_access_logs" {
  description = "Create the ALB access-log S3 bucket and its delivery policy."
  type        = bool
}

variable "cluster_name" {
  description = "ECS cluster name for alarm dimensions."
  type        = string
}

variable "service_name" {
  description = "ECS service name for alarm dimensions."
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix for alarm dimensions."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "Target group ARN suffix for alarm dimensions."
  type        = string
}

variable "db_instance_identifier" {
  description = "RDS instance identifier for alarm dimensions."
  type        = string
}

variable "db_connections_alarm_threshold" {
  description = "DatabaseConnections alarm threshold (~80% of the instance-class connection ceiling)."
  type        = number
}
