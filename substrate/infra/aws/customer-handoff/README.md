# customer-handoff/ — what the customer does before Meridian can deploy

Meridian deploys substrate **into the customer's AWS account** (their VPC,
their IAM, their data residency — production plan §3). This directory holds
the one artifact the customer runs plus the checklist of inputs they provide.

## Customer checklist

1. **Deploy the role template** (CloudFormation — no Terraform assumed on the
   customer side):

   ```
   aws cloudformation deploy \
     --template-file meridian-deploy-role.yaml \
     --stack-name meridian-<engagement>-deploy-role \
     --capabilities CAPABILITY_NAMED_IAM \
     --parameter-overrides \
       MeridianPrincipalArn=<provided-by-meridian> \
       ExternalId=<agreed-out-of-band> \
       EngagementId=<engagement> \
       PermissionsBoundaryArn=<optional-customer-boundary>
   ```

   Return the stack's `RoleArn` output to Meridian.

   The role can **provision** engagement-scoped infrastructure
   (`meridian-<engagement>-*` names, `meridian/<engagement>/*` secrets,
   `meridian:engagement`-tagged resources) and is **explicitly denied**
   customer-data-plane reads: `secretsmanager:GetSecretValue` everywhere,
   `rds-db:connect`, and `s3:GetObject*` outside the engagement
   tfstate/ALB-log buckets. Meridian can deploy but provably cannot read your data.

   If you run **multiple Meridian engagements** in the same account, no
   engagement id may be a prefix of another (e.g. do not use both `acme` and
   `acme-prod`) — permission scoping is prefix-based and the pair would bleed
   into each other.

2. **Exchange the ExternalId out-of-band** (it is the confused-deputy guard on
   the role trust policy; Meridian supplies it on every AssumeRole call).

3. **OIDC**: provide the **issuer URI** of your IdP (Okta, Entra ID, Auth0,
   internal — any conformant OIDC provider) and have your IdP admin register
   the redirect URIs Meridian sends back after the first apply
   (`https://<app-fqdn>/login/oauth2/code/*`). Meridian never provisions or
   configures your IdP.

4. **TLS certificate**: issue or import an ACM certificate in the agreed
   region — a public ACM cert for an internet-facing deployment, or a
   private-CA / imported cert for the default internal posture.

5. **Reachability** (default internal ALB): ensure your VPN / Direct Connect /
   peering can reach the engagement VPC, and tell Meridian which CIDRs should
   be admitted (`alb_allowed_ingress_cidrs`).

6. **Region + CIDR**: agree the AWS region and a VPC CIDR that does not
   overlap your network (default proposal `10.40.0.0/16`).

7. **DNS** (optional but recommended): choose the app FQDN (e.g.
   `substrate.your-domain.com`) and CNAME it to the ALB DNS name Meridian
   returns after the first apply. DNS stays customer-owned.

## What Meridian never gets

- No standing credentials — only `sts:AssumeRole` with your ExternalId.
- No secret reads — the role writes secret values (`PutSecretValue`) during
  setup but is denied `GetSecretValue` everywhere, forever.
- No database access — `rds-db:connect` denied; the DB is in isolated subnets
  reachable only from the app tier.
- No object reads outside the Terraform state and ALB-access-log buckets.

Teardown: deleting this CloudFormation stack revokes Meridian's access
entirely (do it AFTER the engagement stack is destroyed, or Meridian cannot
run the destroy).
