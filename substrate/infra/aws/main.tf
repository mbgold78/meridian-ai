# Per-engagement substrate stack, deployed INTO the customer's AWS account via
# the engagement deploy role (see providers.tf). One shared root-module
# codebase, instantiated per engagement with:
#
#   terraform init  -backend-config=engagements/<id>/backend.hcl
#   terraform plan  -var-file=engagements/<id>/engagement.tfvars
#
# Module order of thought: secrets creates the engagement CMK first (everything
# else encrypts with it); network owns the VPC and ALL security groups (so no
# SG reference cycles between database and service); database and service hang
# off those; observability watches the result.

module "secrets" {
  source = "./modules/secrets"

  engagement_id                = var.engagement_id
  name_prefix                  = local.name_prefix
  aws_region                   = var.aws_region
  deploy_role_arn              = var.deploy_role_arn
  db_password_rotation_counter = var.db_password_rotation_counter
}

module "network" {
  source = "./modules/network"

  name_prefix               = local.name_prefix
  engagement_id             = var.engagement_id
  aws_region                = var.aws_region
  vpc_cidr                  = var.vpc_cidr
  az_count                  = var.az_count
  alb_internal              = var.alb_internal
  alb_allowed_ingress_cidrs = var.alb_allowed_ingress_cidrs
  enable_nat_gateway        = var.enable_nat_gateway
  nat_gateway_per_az        = var.nat_gateway_per_az
  enable_vpc_endpoints      = var.enable_vpc_endpoints
  enable_ssm_endpoints      = var.enable_execute_command
  kms_key_arn               = module.secrets.kms_key_arn
  log_retention_days        = var.log_retention_days
}

module "database" {
  source = "./modules/database"

  name_prefix                 = local.name_prefix
  db_subnet_ids               = module.network.isolated_db_subnet_ids
  db_security_group_id        = module.network.db_security_group_id
  kms_key_arn                 = module.secrets.kms_key_arn
  db_name                     = var.db_name
  db_user                     = var.db_user
  db_master_password          = module.secrets.db_master_password
  db_master_password_version  = var.db_password_rotation_counter
  db_engine_version           = var.db_engine_version
  db_instance_class           = var.db_instance_class
  db_multi_az                 = var.db_multi_az
  db_allocated_storage_gb     = var.db_allocated_storage_gb
  db_max_allocated_storage_gb = var.db_max_allocated_storage_gb
  db_backup_retention_days    = var.db_backup_retention_days
}

module "service" {
  source = "./modules/service"

  name_prefix           = local.name_prefix
  aws_region            = var.aws_region
  vpc_id                = module.network.vpc_id
  app_subnet_ids        = module.network.private_app_subnet_ids
  alb_subnet_ids        = var.alb_internal ? module.network.private_app_subnet_ids : module.network.public_subnet_ids
  alb_internal          = var.alb_internal
  alb_name              = local.alb_name
  target_group_name     = local.target_group_name
  alb_security_group_id = module.network.alb_security_group_id
  app_security_group_id = module.network.app_security_group_id
  efs_security_group_id = module.network.efs_security_group_id
  certificate_arn       = var.certificate_arn

  image_uri             = var.image_uri
  task_cpu              = var.task_cpu
  task_memory           = var.task_memory
  cpu_architecture      = var.cpu_architecture
  desired_count         = var.desired_count
  container_environment = local.container_environment
  container_secrets     = local.container_secrets
  kms_key_arn           = module.secrets.kms_key_arn
  log_group_name        = module.observability.log_group_name

  enable_persistent_storage = var.enable_persistent_storage
  create_ecr_repo           = var.create_ecr_repo
  enable_execute_command    = var.enable_execute_command
  enable_alb_access_logs    = var.enable_alb_access_logs
  access_logs_bucket        = module.observability.alb_access_logs_bucket_id
}

module "observability" {
  source = "./modules/observability"

  name_prefix        = local.name_prefix
  engagement_id      = var.engagement_id
  aws_region         = var.aws_region
  kms_key_arn        = module.secrets.kms_key_arn
  log_retention_days = var.log_retention_days
  alarm_email        = var.alarm_email

  enable_alb_access_logs = var.enable_alb_access_logs

  cluster_name                   = module.service.cluster_name
  service_name                   = module.service.service_name
  alb_arn_suffix                 = module.service.alb_arn_suffix
  target_group_arn_suffix        = module.service.target_group_arn_suffix
  db_instance_identifier         = module.database.identifier
  db_connections_alarm_threshold = var.db_connections_alarm_threshold
}
