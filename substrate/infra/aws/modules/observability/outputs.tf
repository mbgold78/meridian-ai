output "log_group_name" {
  description = "App log group name (consumed by the service module's awslogs configuration)."
  value       = aws_cloudwatch_log_group.app.name
}

output "log_group_arn" {
  description = "App log group ARN."
  value       = aws_cloudwatch_log_group.app.arn
}

output "sns_topic_arn" {
  description = "Engagement alarm topic ARN."
  value       = aws_sns_topic.alarms.arn
}

output "alb_access_logs_bucket_id" {
  description = "Name of the ALB access-log bucket (null when access logs are disabled)."
  value       = try(aws_s3_bucket.alb_logs[0].id, null)

  # ELB validates bucket permissions SYNCHRONOUSLY when access logging is
  # enabled at CreateLoadBalancer time. Without this, the ALB and the bucket
  # policy are parallel-eligible and the first apply races to an
  # "Access Denied for bucket" failure.
  depends_on = [aws_s3_bucket_policy.alb_logs]
}

output "dashboard_name" {
  description = "CloudWatch dashboard name."
  value       = aws_cloudwatch_dashboard.engagement.dashboard_name
}
