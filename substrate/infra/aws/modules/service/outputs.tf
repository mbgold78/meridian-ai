output "alb_dns_name" {
  description = "DNS name of the engagement ALB."
  value       = aws_lb.app.dns_name
}

output "alb_arn_suffix" {
  description = "ALB ARN suffix for CloudWatch metric dimensions."
  value       = aws_lb.app.arn_suffix
}

output "target_group_arn_suffix" {
  description = "Target group ARN suffix for CloudWatch metric dimensions."
  value       = aws_lb_target_group.app.arn_suffix
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "service_name" {
  description = "ECS service name."
  value       = aws_ecs_service.app.name
}

output "ecr_repository_url" {
  description = "Engagement ECR repository URL (null when create_ecr_repo = false)."
  value       = try(aws_ecr_repository.app[0].repository_url, null)
}
