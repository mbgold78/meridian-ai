output "alb_dns_name" {
  description = "DNS name of the engagement ALB. Customer points their app_fqdn CNAME here (DNS is customer-owned, never provisioned)."
  value       = module.service.alb_dns_name
}

output "app_url" {
  description = "URL operators and the customer use to reach substrate (app_fqdn when set, otherwise the raw ALB DNS name)."
  value       = "https://${var.app_fqdn != "" ? var.app_fqdn : module.service.alb_dns_name}"
}

output "ecr_repository_url" {
  description = "Engagement ECR repository URL to crane-copy the CI-built image into (null when create_ecr_repo = false)."
  value       = module.service.ecr_repository_url
}

output "rds_endpoint" {
  description = "RDS Postgres endpoint (host:port) — reachable only from the app security group inside the VPC."
  value       = "${module.database.address}:${module.database.port}"
}

output "db_password_secret_arn" {
  description = "Secrets Manager ARN holding the DB master password (written via write-only arguments; value never in state)."
  value       = module.secrets.db_password_secret_arn
}

output "anthropic_secret_arn" {
  description = "Secrets Manager ARN of the ANTHROPIC_API_KEY shell the operator writes out-of-band (always created; enable_ai_assist gates only the container wiring)."
  value       = module.secrets.anthropic_api_key_secret_arn
}

output "log_group_name" {
  description = "CloudWatch log group receiving the app's structured JSON logs."
  value       = module.observability.log_group_name
}

output "cluster_name" {
  description = "ECS cluster name (for aws ecs CLI day-2 operations)."
  value       = module.service.cluster_name
}

output "service_name" {
  description = "ECS service name (for force-new-deployment, scaling, ECS Exec)."
  value       = module.service.service_name
}

output "sns_alarm_topic_arn" {
  description = "SNS topic ARN that all engagement alarms publish to."
  value       = module.observability.sns_topic_arn
}

output "oidc_redirect_uri_hint" {
  description = "Redirect-URI pattern for the customer's IdP administrator to register against the issuer."
  value       = "https://${var.app_fqdn != "" ? var.app_fqdn : module.service.alb_dns_name}/login/oauth2/code/*"
}
