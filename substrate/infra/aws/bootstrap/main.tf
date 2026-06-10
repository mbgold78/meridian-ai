# Bootstrap root — run ONCE per engagement, before the main stack.
# Creates the Terraform state backend in the CUSTOMER account:
#   * S3 bucket  meridian-<engagement>-tfstate-<account-id>
#     (versioned, SSE-KMS, public access blocked, TLS-only, noncurrent-version
#     expiry at 90 days, S3-native lockfile — no DynamoDB, design §A3)
#   * KMS CMK    alias/meridian/<engagement>/tfstate (rotation enabled)
#
# Chicken-and-egg: this root's own state starts LOCAL, then migrates into the
# bucket it just created — see bootstrap/README.md. The bucket is the LAST
# thing destroyed at engagement teardown.

data "aws_caller_identity" "current" {}

locals {
  bucket_name = "meridian-${var.engagement_id}-tfstate-${data.aws_caller_identity.current.account_id}"
}

resource "aws_kms_key" "tfstate" {
  description             = "Meridian ${var.engagement_id} Terraform state encryption key"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  # Default key policy (account root admin); the deploy role operates through
  # IAM permissions granted by the customer-handoff CloudFormation role.
}

resource "aws_kms_alias" "tfstate" {
  name          = "alias/meridian/${var.engagement_id}/tfstate"
  target_key_id = aws_kms_key.tfstate.key_id
}

resource "aws_s3_bucket" "tfstate" {
  bucket = local.bucket_name
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.tfstate.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.tfstate.arn,
          "${aws_s3_bucket.tfstate.arn}/*"
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.tfstate]
}

resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    id     = "expire-noncurrent-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }

  depends_on = [aws_s3_bucket_versioning.tfstate]
}
