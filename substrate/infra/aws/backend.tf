# Partial backend configuration — every value comes from the per-engagement
# backend file:
#
#   terraform init -backend-config=engagements/<id>/backend.hcl
#
# State lives in an S3 bucket in the CUSTOMER account (created by bootstrap/),
# SSE-KMS encrypted, versioned, and locked with the S3-native lockfile
# (use_lockfile = true — no DynamoDB table). State must never leave the
# customer account: it contains infrastructure metadata about their environment.
terraform {
  backend "s3" {}
}
