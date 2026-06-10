output "tfstate_bucket" {
  description = "State bucket name — paste into engagements/<id>/backend.hcl as `bucket`."
  value       = aws_s3_bucket.tfstate.id
}

output "tfstate_kms_key_arn" {
  description = "State encryption key ARN — paste into engagements/<id>/backend.hcl as `kms_key_id`."
  value       = aws_kms_key.tfstate.arn
}
