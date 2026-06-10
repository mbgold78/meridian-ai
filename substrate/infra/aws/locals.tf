locals {
  name_prefix = "meridian-${var.engagement_id}"

  # ALB and target-group names are capped at 32 characters by AWS. An
  # engagement_id at the 20-char validation cap would overflow, so truncate and
  # strip any trailing hyphen left by the cut (names may not end with '-').
  alb_name          = trimsuffix(substr("${local.name_prefix}-alb", 0, 32), "-")
  target_group_name = trimsuffix(substr("${local.name_prefix}-tg", 0, 32), "-")

  # CORS default comes from app_fqdn only. The "fall back to the ALB DNS name"
  # idea is circular (a service output feeding the service's own input), so when
  # both cors_allowed_origins and app_fqdn are empty the env var is omitted and
  # the operator sets cors_allowed_origins to the ALB DNS name after the first
  # apply — documented in substrate/docs/deploy.md (step 5 / step 7).
  cors_origins = length(var.cors_allowed_origins) > 0 ? join(",", var.cors_allowed_origins) : (var.app_fqdn != "" ? "https://${var.app_fqdn}" : "")

  # Container environment, assembled in root so modules stay app-agnostic.
  # Fixed values per the substrate env contract (docker-compose.yml +
  # application-postgres.yml): the postgres Spring profile is always active,
  # DB endpoint comes from the database module, and the DB password is NEVER
  # in `environment` — it rides the ECS `secrets` channel below.
  container_environment = merge(
    {
      SPRING_PROFILES_ACTIVE         = "postgres"
      SUBSTRATE_DB_HOST              = module.database.address
      SUBSTRATE_DB_PORT              = tostring(module.database.port)
      SUBSTRATE_DB_NAME              = var.db_name
      SUBSTRATE_DB_USER              = var.db_user
      SUBSTRATE_AUTH_MODE            = var.auth_mode
      SUBSTRATE_OIDC_ISSUER_URI      = var.oidc_issuer_uri
      SUBSTRATE_ENGAGEMENT_ID        = var.engagement_id
      SUBSTRATE_ORCHESTRATOR_ENABLED = tostring(var.orchestrator_enabled)
    },
    # Empty values are omitted entirely so application defaults apply.
    var.orchestrator_base_url != "" ? { SUBSTRATE_ORCHESTRATOR_URL = var.orchestrator_base_url } : {},
    var.llm_model != "" ? { SUBSTRATE_LLM_MODEL = var.llm_model } : {},
    local.cors_origins != "" ? { SUBSTRATE_CORS_ALLOWEDORIGINS = local.cors_origins } : {},
  )

  # Secrets injected via ECS `secrets` valueFrom (read at task launch by the
  # EXECUTION role — the deploy role's GetSecretValue deny does not apply to it).
  # ANTHROPIC_API_KEY wiring is conditional (the secret SHELL always exists):
  # ECS fails task launch on a value-less secret, so the operator writes the
  # value first and only then flips enable_ai_assist — the two-phase enable
  # flow in docs/deploy.md step 6.
  container_secrets = merge(
    { SUBSTRATE_DB_PASSWORD = module.secrets.db_password_secret_arn },
    var.enable_ai_assist ? { ANTHROPIC_API_KEY = module.secrets.anthropic_api_key_secret_arn } : {},
  )
}
