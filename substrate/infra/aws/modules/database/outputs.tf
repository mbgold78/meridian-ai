output "address" {
  description = "RDS instance hostname (feeds SUBSTRATE_DB_HOST)."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "RDS instance port (feeds SUBSTRATE_DB_PORT)."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Database name."
  value       = aws_db_instance.this.db_name
}

output "identifier" {
  description = "RDS instance identifier (used by observability alarms and the dashboard)."
  value       = aws_db_instance.this.identifier
}

output "arn" {
  description = "RDS instance ARN."
  value       = aws_db_instance.this.arn
}
