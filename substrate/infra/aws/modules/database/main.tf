# Engagement RDS Postgres — single instance, isolated subnets, engagement CMK,
# TLS enforced at the engine (rds.force_ssl = 1).
#
# Password handling: password_wo / password_wo_version (write-only arguments,
# Terraform >= 1.11). The value arrives as an ephemeral from the secrets module
# and is never written to state or plan.
#
# CAUTION (write-only semantics): write-only arguments are sent on CREATE and
# whenever *_wo_version changes. If this instance is ever REPLACED without
# bumping db_password_rotation_counter, the new instance receives a freshly
# generated password while the Secrets Manager secret keeps the old one —
# after any RDS replacement, bump the counter and apply again
# (docs/deploy.md Day-2 covers this).

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-db"
  description = "Isolated-tier subnets for the engagement database"
  subnet_ids  = var.db_subnet_ids

  tags = { Name = "${var.name_prefix}-db" }
}

resource "aws_db_parameter_group" "this" {
  # name_prefix + create_before_destroy instead of a fixed name: a major
  # engine-version move (family postgres16 -> postgres17) forces REPLACEMENT,
  # and with a fixed name the delete-then-create ordering wedges (the old
  # group is still attached to the running instance, while create-first would
  # collide on the name). This way the new-family group is created, the
  # instance repoints, and the old group is deleted afterwards.
  name_prefix = "${var.name_prefix}-db-params-"
  family      = "postgres${split(".", var.db_engine_version)[0]}"
  description = "Engagement Postgres parameters — forces TLS for all client connections"

  lifecycle {
    create_before_destroy = true
  }

  # rds.force_ssl is dynamic on RDS PostgreSQL; applies without reboot.
  # Port-fleet coordination flag: the app's JDBC URL does not pin sslmode yet —
  # pgjdbc negotiates TLS, verified in the deploy runbook (deploy.md step 7).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "this" {
  identifier = "${var.name_prefix}-db"

  engine                     = "postgres"
  engine_version             = var.db_engine_version
  auto_minor_version_upgrade = true
  instance_class             = var.db_instance_class
  multi_az                   = var.db_multi_az
  db_subnet_group_name       = aws_db_subnet_group.this.name
  parameter_group_name       = aws_db_parameter_group.this.name
  vpc_security_group_ids     = [var.db_security_group_id]
  publicly_accessible        = false

  db_name             = var.db_name
  username            = var.db_user
  password_wo         = var.db_master_password
  password_wo_version = var.db_master_password_version
  port                = 5432

  storage_type          = "gp3"
  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_max_allocated_storage_gb
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  backup_retention_period   = var.db_backup_retention_days
  backup_window             = "03:00-04:00"
  maintenance_window        = "sun:04:30-sun:05:30"
  copy_tags_to_snapshot     = true
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name_prefix}-db-final"

  performance_insights_enabled    = true
  performance_insights_kms_key_id = var.kms_key_arn
  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = { Name = "${var.name_prefix}-db" }
}
