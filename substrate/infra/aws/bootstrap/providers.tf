# Identical assume-role shape to the main root: the customer-account deploy
# role with the out-of-band ExternalId (TF_VAR_external_id).
provider "aws" {
  region = var.aws_region

  assume_role {
    role_arn     = var.deploy_role_arn
    external_id  = var.external_id
    session_name = "meridian-substrate-bootstrap"
  }

  default_tags {
    tags = {
      "meridian:engagement" = var.engagement_id
      "meridian:product"    = "substrate"
      "meridian:managed-by" = "terraform"
    }
  }
}
