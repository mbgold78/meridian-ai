# Substrate deploy runbook — per-engagement, into the customer's AWS account

Audience: a **Meridian operator** deploying substrate for one engagement, using
customer-granted credentials. The deploy posture (production plan §3): the
stack runs **in the customer's account** — their VPC, their IAM, their data
residency. Meridian-hosted is testing-only. Substrate may deploy with or
without the orchestrator sibling (whole-or-in-part); this runbook covers the
substrate stack.

Tooling map: [`substrate/infra/aws/README.md`](../infra/aws/README.md).
Customer-side prerequisites: [`substrate/infra/aws/customer-handoff/README.md`](../infra/aws/customer-handoff/README.md).

> **Validation note:** `terraform fmt -check` and `terraform validate` run in
> CI — the `terraform` job in `.github/workflows/substrate-ci.yml`
> (`init -backend=false`: no state, no credentials, no AWS calls). Terraform
> is **not installed on Meridian dev machines**, so CI plus review — not
> local validation — is the quality gate for changes to `infra/aws/**`.

Operator workstation requirements: `terraform >= 1.11`, `aws` CLI v2,
[`crane`](https://github.com/google/go-containerregistry) (image copy by
digest). Your AWS profile must resolve to the Meridian operator principal the
customer trusted in the deploy role (`MeridianPrincipalArn`).

Throughout, `<id>` is the engagement id (also the Terraform `engagement_id`),
`<acct>` is the customer account id, `<region>` the agreed region.

---

## Deploy shapes — testing vs production (plan §3)

Same image, three shapes. The shape determines who hosts, which IdP signs
tokens, and what data is allowed in:

| Shape | Where it runs | IdP | Data allowed | Tooling |
|---|---|---|---|---|
| **Local dev** | Docker Compose on a Docker-capable workstation (`substrate/docker-compose.yml`); Docker-less dev uses `gradlew :app:bootRun` (H2, anonymous mode) | Keycloak container (`infra/keycloak/realm-substrate.json`) | Fixtures only | compose |
| **Meridian-hosted testing** | Meridian's own AWS account, fixture engagements, spun up ONLY to validate this deploy tooling — **zero AWS bills during the build phase; nothing in this repo provisions anything by itself** | Keycloak (throwaway) | Fixtures only — **never customer data** | this runbook, pointed at a Meridian account |
| **Customer production** | The customer's AWS account — their VPC, their IAM, their data residency | **Customer's IdP** (Okta, Entra ID, Auth0, internal — any conformant OIDC issuer) | The engagement's data; it never leaves their account | this runbook |

The rest of this document is written for the customer-production shape; a
Meridian-hosted testing run follows the identical steps with Meridian standing
in as "the customer" (own account, own deploy role, Keycloak as IdP).

## Keycloak vs customer IdP — the config swap

Auth is generic OIDC (plan §4): Keycloak is the **throwaway test IdP**, never
a production dependency. The swap is configuration only — no image rebuild:

| Knob | Compose / testing (Keycloak) | Customer production |
|---|---|---|
| `SUBSTRATE_AUTH_MODE` | `oidc` (compose default; `anonymous` remains the Docker-less dev default) | `oidc` — pinned by the `auth_mode` tfvar validation |
| `SUBSTRATE_OIDC_ISSUER_URI` | `http://keycloak:8080/realms/substrate` | Customer issuer (step 0.4) via the `oidc_issuer_uri` tfvar |
| Roles claim (`substrate.auth.oidc.claims.roles`) | `realm_access.roles` (Keycloak default) | Dot-path into the customer's token, per engagement |
| Persona mapping (`substrate.auth.oidc.persona.role-map`, `persona.default-level`) | Dev realm roles `substrate-admin\|operator\|exec\|coo` | Customer group/role names, agreed per engagement |
| Admin gate (`substrate.auth.oidc.admin-roles`) | `substrate-admin` | Customer's admin role name |
| Redirect URI registration | Pre-seeded in the dev realm | Customer IdP admin registers the step-5 `oidc_redirect_uri_hint` |

Issuer and auth mode are first-class tfvars; the role-mapping keys ride the
`app_extra_environment` tfvar (plain env vars on the task definition — e.g.
`SUBSTRATE_AUTH_OIDC_CLAIMS_ROLES` via Spring relaxed binding; structured keys
like the persona role-map via `SPRING_APPLICATION_JSON`, see
`engagements/example/engagement.tfvars.example`). It is plain-config ONLY —
secret material rides the ECS secrets channel, never tfvars. Full key
reference: [`auth.md`](auth.md).

---

## Step 0 — Customer-handoff prerequisites

Everything here is done by or with the customer, **before** any Terraform runs
(checklist detail in `infra/aws/customer-handoff/README.md`):

1. **Engagement id agreed.** Matches `^[a-z0-9][a-z0-9-]{2,19}$`. HARD RULE:
   it must not be a prefix of any other active engagement id in the same
   customer account (never `acme` and `acme-prod` together — IAM scoping is
   prefix-globbed).
2. **Deploy role deployed by the customer** via CloudFormation
   (`customer-handoff/meridian-deploy-role.yaml`). The customer returns the
   stack's `RoleArn` output — that is your `deploy_role_arn`.
3. **ExternalId exchanged out-of-band.** It is the confused-deputy guard on
   the role trust policy. It lives ONLY in your operator environment:

   ```
   export TF_VAR_external_id=<agreed-external-id>
   ```

   Never commit it — not in tfvars, not in `backend.hcl` (see step 2).
4. **OIDC issuer URI** from the customer IdP (input only — Meridian never
   provisions or configures the IdP). Feeds `oidc_issuer_uri`.
5. **ACM certificate ARN** in the agreed region (public cert for an
   internet-facing ALB; imported / private-CA cert for the default internal
   posture). Feeds `certificate_arn`.
6. **Region + VPC CIDR** agreed (no overlap with the customer network), and
   the **ingress CIDRs** admitted to the ALB (`alb_allowed_ingress_cidrs` —
   required, there is deliberately no default-open).
7. **App FQDN** chosen (optional but recommended; customer-owned DNS).
8. **Anthropic API key** available to the operator if the engagement buys AI
   assist (used in step 6, never before).

## Step 1 — Bootstrap the state backend (run once per engagement)

Per [`infra/aws/bootstrap/README.md`](../infra/aws/bootstrap/README.md):

```
cd substrate/infra/aws/bootstrap
terraform init
terraform apply \
  -var engagement_id=<id> \
  -var aws_region=<region> \
  -var deploy_role_arn=arn:aws:iam::<acct>:role/meridian-<id>-deploy
# TF_VAR_external_id already exported (step 0.3)
```

Record the two outputs — `tfstate_bucket` and `tfstate_kms_key_arn` — then
migrate bootstrap's own local state into the bucket it just created
(re-init with `-migrate-state` under the `substrate/bootstrap.tfstate` key;
exact commands in the bootstrap README). Delete the local `terraform.tfstate*`
files after a successful migration.

## Step 2 — Create the engagement directory

```
cd substrate/infra/aws
mkdir engagements/<id>
cp engagements/example/backend.hcl.example      engagements/<id>/backend.hcl
cp engagements/example/engagement.tfvars.example engagements/<id>/engagement.tfvars
```

Edit both:

* `backend.hcl` — paste the step-1 outputs (`bucket`, `kms_key_id`), set
  `region` and the `assume_role.role_arn`. **Do not add `external_id` to the
  file** — it is supplied at init time (step 3). Engagement directories ARE
  committed; they must contain no credentials and no ExternalId.
* `engagement.tfvars` — fill per the agreed inputs from step 0. On the very
  first deploy leave `image_uri` at the placeholder digest (step 4 fixes it),
  keep `enable_ai_assist = false` (step 6 flips it).

## Step 3 — Init and plan

```
cd substrate/infra/aws
terraform init -backend-config=engagements/<id>/backend.hcl \
  -backend-config="assume_role={role_arn=\"arn:aws:iam::<acct>:role/meridian-<id>-deploy\",external_id=\"$TF_VAR_external_id\"}"
terraform plan -var-file=engagements/<id>/engagement.tfvars
```

The second `-backend-config` flag injects the ExternalId into the backend's
assume-role without ever committing it. Expect the first plan to be
all-creates; review it against the engagement contract before applying
anything.

## Step 4 — Two-pass image flow (first deploy only)

CI builds the image on every substrate push (the `container` job in
`.github/workflows/substrate-ci.yml` — the repo's only Docker-capable lane; it
also asserts the non-root user and healthcheck). The push step is **guarded**:
it runs only on `main` and only when the `SUBSTRATE_REGISTRY` /
`SUBSTRATE_REGISTRY_USERNAME` / `SUBSTRATE_REGISTRY_PASSWORD` secrets are
configured — with no registry configured, CI is build-only and nothing leaves
the runner. The pushed digest is printed at the end of the job log.

The deploy consumes an **image URI digest-pinned into the engagement's own
registry**. On the first deploy that registry does not exist yet, hence two
passes:

1. **Pass 1 — create just the ECR repository:**

   ```
   terraform apply -var-file=engagements/<id>/engagement.tfvars \
     -target=module.service.aws_ecr_repository.app
   ```

2. **Copy the CI-built digest into the engagement registry** (digest is
   preserved by `crane copy`):

   ```
   aws ecr get-login-password --region <region> | \
     crane auth login <acct>.dkr.ecr.<region>.amazonaws.com -u AWS --password-stdin
   crane copy <ci-registry>/substrate-app@sha256:<digest> \
     <acct>.dkr.ecr.<region>.amazonaws.com/meridian-<id>-substrate
   ```

   (Resolve a CI tag to its digest with `crane digest <ci-uri>`.)

3. **Pin `image_uri`** in `engagement.tfvars` to the engagement-registry URI
   with the same `@sha256:` digest. The variable validation rejects anything
   not digest-pinned.

Skip pass 1 when `create_ecr_repo = false` (customer-supplied registry) — just
copy/pin against their registry. For later image updates see Day-2.

## Step 5 — First full apply + post-apply registration

```
terraform apply -var-file=engagements/<id>/engagement.tfvars
```

This creates the full stack: VPC + subnets + endpoints, engagement CMK,
secrets (including the value-less `ANTHROPIC_API_KEY` shell — always created,
unwired until step 6), RDS Postgres (TLS-forced), EFS, ECS service behind the
ALB, alarms/dashboard/log groups, ALB access-log bucket.

Then complete the registrations that need apply outputs:

1. **DNS (customer-owned):** give the customer `alb_dns_name` to CNAME their
   `app_fqdn` to. Meridian never provisions DNS.
2. **OIDC redirect URI:** send the customer's IdP admin the
   `oidc_redirect_uri_hint` output
   (`https://<app-fqdn>/login/oauth2/code/*`) to register against the issuer.
3. **CORS:** if both `cors_allowed_origins` and `app_fqdn` were empty, the
   env var was omitted on first apply. Set `cors_allowed_origins` to
   `["https://<alb_dns_name>"]` (or the final FQDN origin) in
   `engagement.tfvars` and `terraform apply` again.

## Step 6 — Enable AI assist (two-phase, only if contracted)

The secret **shell** `meridian/<id>/anthropic-api-key` already exists after
step 5 (it is always created, value-less, and not wired into the container).
ECS fails task launches on a value-less secret, so the value must land
**before** the wiring flips on:

1. **Write the key** (the deploy role may `PutSecretValue`; it is explicitly
   denied `GetSecretValue` — Meridian writes, never reads back):

   ```
   aws secretsmanager put-secret-value \
     --secret-id meridian/<id>/anthropic-api-key \
     --secret-string '<anthropic-api-key>'
   ```

2. **Flip the wiring:** set `enable_ai_assist = true` in `engagement.tfvars`
   and `terraform apply`. This adds `ANTHROPIC_API_KEY` to the task
   definition's secrets and rolls a new deployment.

`enable_nat_gateway = true` is required for the Anthropic egress path (task
egress is scoped to 443 outbound; AWS control-plane traffic rides the VPC
endpoints). To revoke later: flip `enable_ai_assist = false` and apply, then
`put-secret-value` a dummy to invalidate the old key with Anthropic.

## Step 7 — Verification (smoke checklist)

Run every check; "apply exited 0" is not "product worked".

1. **App health** (from a CIDR admitted in `alb_allowed_ingress_cidrs`; for
   the default internal ALB that means over the customer VPN/peering):

   ```
   curl -fsS https://$(terraform output -raw alb_dns_name)/actuator/health
   # expect: {"status":"UP", ...} — the db component UP is part of the TLS proof below
   ```

   If DNS/cert are live, prefer `terraform output -raw app_url`.
2. **ECS service stable:**

   ```
   aws ecs describe-services \
     --cluster $(terraform output -raw cluster_name) \
     --services $(terraform output -raw service_name) \
     --query 'services[0].{running:runningCount,desired:desiredCount,events:events[0].message}'
   ```

   `running == desired` and no circuit-breaker rollback events.
3. **TLS-to-RDS check** (referenced by `modules/database/main.tf`): the
   parameter group forces `rds.force_ssl = 1`, and the app's JDBC URL does not
   pin `sslmode` — pgjdbc negotiates TLS. Verify both halves:

   ```
   aws rds describe-db-parameters \
     --db-parameter-group-name $(aws rds describe-db-instances \
        --db-instance-identifier meridian-<id>-db \
        --query 'DBInstances[0].DBParameterGroups[0].DBParameterGroupName' --output text) \
     --query "Parameters[?ParameterName=='rds.force_ssl'].ParameterValue" --output text
   # expect: 1
   ```

   With `force_ssl = 1` the engine **rejects** plaintext connections, so the
   health check's `db: UP` (check 1) proves the app connected over TLS.
   (Direct `pg_stat_ssl` inspection is deliberately impossible for the
   operator: `rds-db:connect` is denied and the DB sits in isolated subnets.)
4. **Structured logs flowing:**

   ```
   aws logs tail $(terraform output -raw log_group_name) --since 10m
   ```

   Expect the app's JSON log lines (logback) — not raw stack-trace soup.
5. **Alarms + dashboard:** the `alarm_email` recipient confirmed the SNS
   subscription; the `meridian-<id>-substrate` CloudWatch dashboard renders
   ALB/ECS/RDS widgets with data.
6. **ALB access logs landing** (when enabled):

   ```
   aws s3 ls s3://meridian-<id>-alblogs-<acct>/alb/ --recursive | head
   ```
7. **Login round-trip:** a customer test user completes the OIDC login at the
   app URL (validates issuer config + redirect URI registration).

---

## Day-2 operations

### Rotate the DB master password

`db_password_rotation_counter` drives the write-only password plumbing
(`secret_string_wo_version` / `password_wo_version`). One counter bump rotates
both the Secrets Manager value and the RDS master password in the same apply:

1. Bump `db_password_rotation_counter` by 1 in `engagement.tfvars`.
2. `terraform apply -var-file=engagements/<id>/engagement.tfvars`
3. Roll tasks so they fetch the new value at launch (running tasks injected
   the old password at their launch):

   ```
   aws ecs update-service --cluster $(terraform output -raw cluster_name) \
     --service $(terraform output -raw service_name) --force-new-deployment
   ```

   Expect a brief window where pooled connections survive but new connections
   from old tasks fail; the rolling deployment closes it.

### After any RDS instance REPLACEMENT

Write-only semantics caution (see `modules/database/main.tf`): a replacement
without a counter bump gives the new instance a freshly generated password
while the secret keeps the old one. **Always bump
`db_password_rotation_counter` and apply again after a replacement**, then
force a new ECS deployment as above.

### Deploy a new image version

`crane copy` the new CI digest into the engagement registry (step 4.2), update
`image_uri` to the new digest, `terraform apply`. The new task definition
rolls out under the deployment circuit breaker (auto-rollback on failure).

### Rollback

Three layers, in escalation order:

1. **Automatic (no operator action):** a rollout whose tasks fail to reach
   steady state trips the ECS deployment circuit breaker, which rolls the
   service back to the last working task definition. The `terraform apply`
   fails loudly; the bad `image_uri` is still in tfvars — fix or revert it
   before the next apply.
2. **Image rollback (bad release that passed health checks):** re-pin
   `image_uri` in `engagement.tfvars` to the previous digest (the engagement
   registry retains every copied digest; tags are immutable) and
   `terraform apply`. Run the step-7 smoke checklist afterwards.
   **Flyway caveat:** migrations are forward-only — if the bad release shipped
   a schema migration, the previous image must still boot against the migrated
   schema. Verify before rolling back past a migration boundary; if it cannot,
   roll FORWARD with a fixed image instead.
3. **Config rollback:** tfvars are committed per engagement, so `git revert`
   the offending change and `terraform apply`. For secrets, write the previous
   value with `put-secret-value` and force a new deployment (tasks read
   secrets at launch).
4. **Data rollback (last resort, customer decision):** RDS PITR and snapshots
   stay in the customer's account, and a restore always creates a NEW
   instance **outside Terraform state** — this is incident response, not a
   tfvars change. Agree the procedure with the customer under the engagement
   contract, restore, then reconcile Terraform deliberately (state surgery or
   cutover plan) before any further applies.

### Major Postgres version upgrade

Set `db_engine_version` to the new major. The parameter-group family change
forces a create-before-destroy replacement of the parameter group (handled by
`name_prefix` + lifecycle in the database module); review the plan carefully
and schedule with the customer — the engine upgrade itself causes downtime on
a single-instance engagement.

### Scaling / posture changes

`desired_count`, `task_cpu`/`task_memory`, `db_instance_class`,
`db_multi_az`, `nat_gateway_per_az` are all tfvars-driven applies. Remember to
revisit `db_connections_alarm_threshold` when changing `db_instance_class`.

---

## Teardown (engagement close-out)

The destroy path has **deliberate gates** — retained images, delivered logs,
and RDS deletion protection block a one-shot `terraform destroy`. That is by
design; clear them consciously, in this order, only when the engagement is
contractually closed:

1. **Archive the final Terraform state** per the engagement contract (it
   contains infrastructure metadata only — no secret values — but it must not
   leave the customer account except per contract).
2. **Empty the ALB access-log bucket** (logs expire at 90 days, but recent
   deliveries block deletion):

   ```
   aws s3 rm s3://meridian-<id>-alblogs-<acct> --recursive
   ```
3. **Delete the engagement's ECR images:**

   ```
   aws ecr batch-delete-image --repository-name meridian-<id>-substrate \
     --image-ids "$(aws ecr list-images --repository-name meridian-<id>-substrate \
       --query 'imageIds' --output json)"
   ```
4. **Disable RDS deletion protection** (intentionally hardcoded on in the
   module; disable out-of-band only at teardown):

   ```
   aws rds modify-db-instance --db-instance-identifier meridian-<id>-db \
     --no-deletion-protection --apply-immediately
   ```

   The destroy still takes a final snapshot (`meridian-<id>-db-final`,
   `skip_final_snapshot = false`) — agree its retention/handover with the
   customer; it stays in their account.
5. **Destroy the main stack:**

   ```
   terraform destroy -var-file=engagements/<id>/engagement.tfvars
   ```
6. **Bootstrap goes LAST** (per `bootstrap/README.md`): empty the tfstate
   bucket — all object versions — then destroy the bootstrap stack.
7. **Customer deletes the CloudFormation deploy-role stack**, revoking
   Meridian's access entirely. Doing this before steps 5-6 locks Meridian out
   of its own destroy.
