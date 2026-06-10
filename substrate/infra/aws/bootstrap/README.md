# bootstrap/ — per-engagement state backend

Run **once per engagement**, before the main stack. Creates the S3 state
bucket (versioned, SSE-KMS, TLS-only, public-blocked) and its CMK in the
**customer's** account. No DynamoDB lock table — the main stack uses the
S3-native lockfile (`use_lockfile = true`, Terraform >= 1.10).

## Chicken-and-egg flow (standard pattern)

1. First apply runs with **local state** in this directory:

   ```
   terraform init
   terraform apply \
     -var engagement_id=<id> \
     -var aws_region=<region> \
     -var deploy_role_arn=arn:aws:iam::<customer-acct>:role/meridian-<id>-deploy
   # external_id comes from TF_VAR_external_id in the operator environment
   ```

2. Record the two outputs (`tfstate_bucket`, `tfstate_kms_key_arn`) into
   `../engagements/<id>/backend.hcl`.

3. Migrate bootstrap's own state into the bucket it just created by adding a
   backend block here is **not** done — instead keep it simple: re-init this
   root against the same bucket under a distinct key:

   ```
   terraform init -migrate-state \
     -backend-config="bucket=<tfstate_bucket>" \
     -backend-config="key=substrate/bootstrap.tfstate" \
     -backend-config="region=<region>" \
     -backend-config="use_lockfile=true" \
     -backend-config="encrypt=true" \
     -backend-config="kms_key_id=<tfstate_kms_key_arn>" \
     -backend-config="assume_role={role_arn=\"arn:aws:iam::<acct>:role/meridian-<id>-deploy\",external_id=\"$TF_VAR_external_id\"}"
   ```

   (This requires a one-line `terraform { backend "s3" {} }` block; add it at
   migration time or pass `-backend=true` config at init. Keeping bootstrap
   state local for the life of the engagement is also acceptable — it contains
   only the bucket/key identifiers, no secrets.)

4. Delete the local `terraform.tfstate*` files after a successful migration.

## Teardown ordering

At engagement teardown the main stack is destroyed FIRST; this bucket (and the
bootstrap stack) go LAST, after the final state of the main stack is archived
per the engagement contract. `terraform destroy` here will refuse to delete a
non-empty versioned bucket — empty it deliberately (all versions) when, and
only when, the engagement is contractually closed.

Note: `terraform fmt` / `terraform validate` for this directory run in CI (CI
wiring lands later); terraform is not installed on dev machines.
