output "vpc_id" {
  description = "Engagement VPC id."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "Engagement VPC CIDR block."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public-tier subnet ids (internet-facing ALB / NAT only)."
  value       = aws_subnet.public[*].id
}

output "private_app_subnet_ids" {
  description = "Private app-tier subnet ids (Fargate tasks, internal ALB, EFS mount targets, interface endpoints)."
  value       = aws_subnet.private_app[*].id
}

output "isolated_db_subnet_ids" {
  description = "Isolated db-tier subnet ids (no NAT route)."
  value       = aws_subnet.isolated_db[*].id
}

output "alb_security_group_id" {
  description = "Security group for the engagement ALB."
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "Security group for substrate-app Fargate tasks."
  value       = aws_security_group.app.id
}

output "db_security_group_id" {
  description = "Security group for the RDS instance."
  value       = aws_security_group.db.id
}

output "efs_security_group_id" {
  description = "Security group for EFS mount targets."
  value       = aws_security_group.efs.id
}

output "vpce_security_group_id" {
  description = "Security group for VPC interface endpoints."
  value       = aws_security_group.vpce.id
}
