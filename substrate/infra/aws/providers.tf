# Single provider targeting the CUSTOMER account via the engagement deploy role
# created by customer-handoff/meridian-deploy-role.yaml. The ExternalId is the
# confused-deputy guard agreed out-of-band with the customer; it is supplied via
# the TF_VAR_external_id environment variable, never committed.
provider "aws" {
  region = var.aws_region

  assume_role {
    role_arn     = var.deploy_role_arn
    external_id  = var.external_id
    session_name = "meridian-substrate-deploy"
  }

  default_tags {
    # Control tags LAST so var.tags can never clobber them: every engagement-
    # scoping IAM condition in the deploy role (aws:RequestTag/aws:ResourceTag
    # on meridian:engagement) depends on this value being right. var.tags is
    # additionally validated to reject meridian:-prefixed keys.
    tags = merge(
      var.tags,
      {
        "meridian:engagement" = var.engagement_id
        "meridian:product"    = "substrate"
        "meridian:managed-by" = "terraform"
      },
    )
  }
}
