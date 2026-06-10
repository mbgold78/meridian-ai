# Terraform >= 1.11 is required for write-only arguments
# (aws_db_instance.password_wo, aws_secretsmanager_secret_version.secret_string_wo)
# and for S3-native state locking (use_lockfile in the backend config).
#
# NOTE: exact write-only-argument availability against the pinned providers is
# verified by `terraform fmt -check` / `terraform validate` in CI — terraform is
# not installed on dev machines, so do not assume local validation has run.
terraform {
  required_version = ">= 1.11, < 2.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    # >= 3.7 for the ephemeral `random_password` resource (never lands in state).
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}
