# substrate/infra/aws — per-engagement deploy into the customer's AWS account

This is the deploy tooling for the product thesis (production plan §3):
substrate runs **in the customer's account** — their VPC, their IAM, their
data residency. Meridian-hosted is testing-only. One shared root module,
instantiated per engagement with a separate backend + tfvars; nothing is
shared between engagements, and there is no cross-engagement path.

**The operator runbook is [`substrate/docs/deploy.md`](../../docs/deploy.md).**
Start there. This README is only a map.

```
infra/aws/
├── versions.tf / providers.tf / backend.tf   # TF >= 1.11, aws ~> 6.0, assume-role provider, partial S3 backend
├── variables.tf / locals.tf / main.tf / outputs.tf
├── engagements/example/                      # copy per engagement: backend.hcl + engagement.tfvars
├── bootstrap/                                # run-once state bucket + CMK (see bootstrap/README.md)
├── customer-handoff/                         # CloudFormation role the CUSTOMER deploys + checklist
└── modules/
    ├── network/         # VPC, 3 subnet tiers, NAT, VPC endpoints, ALL security groups, flow logs
    ├── secrets/         # engagement CMK, ephemeral+write-only DB password, secret shells
    ├── database/        # RDS Postgres 16, force_ssl, CMK, PI, deletion protection
    ├── service/         # ECS Fargate, ALB (TLS 1.2+/1.3), EFS (uid 1001), ECR, IAM
    └── observability/   # CW logs/alarms/dashboard, SNS, ALB access-log bucket
```

Instantiate:

```
terraform init  -backend-config=engagements/<id>/backend.hcl
terraform plan  -var-file=engagements/<id>/engagement.tfvars
terraform apply -var-file=engagements/<id>/engagement.tfvars
```

`TF_VAR_external_id` must be set in the operator environment (never in tfvars).

**Validation note:** `terraform fmt -check` and `terraform validate` run in CI
— the `terraform` job in `.github/workflows/substrate-ci.yml`
(`init -backend=false`: no credentials, no state, no AWS calls). Terraform is
not installed on dev machines, so CI plus review — not local validation — is
the quality gate for changes here. Nothing is ever planned or applied from CI.
