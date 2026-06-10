# Same pin as the main root: keeps one Terraform binary per operator and CI
# job. (Bootstrap itself only needs >= 1.10 for the S3-native lockfile the main
# stack will use against the bucket created here.)
terraform {
  required_version = ">= 1.11, < 2.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
