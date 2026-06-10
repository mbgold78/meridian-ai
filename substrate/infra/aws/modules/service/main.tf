# ECS Fargate service for substrate-app, fronted by an ALB (TLS terminates at
# the ALB; ALB->task traffic is plaintext :8080 confined to the SG pair inside
# private subnets — design §A7). Optional EFS keeps the app's filesystem roots
# (data/uploads/staging under /opt/substrate, container uid 1001 per the
# Dockerfile) durable across deploys — Fargate task storage is ephemeral.

locals {
  container_name = "substrate-app"

  # Maps the application.yml filesystem roots (./data, ./uploads, ./staging,
  # resolved against WORKDIR /opt/substrate) to EFS access points.
  efs_paths = {
    data    = "/data"
    uploads = "/uploads"
    staging = "/staging"
  }

  mount_points = var.enable_persistent_storage ? [
    for name, path in local.efs_paths : {
      sourceVolume  = name
      containerPath = "/opt/substrate${path}"
      readOnly      = false
    }
  ] : []

  # Sorted for deterministic task-definition JSON (no perpetual diffs).
  environment = [
    for name in sort(keys(var.container_environment)) : {
      name  = name
      value = var.container_environment[name]
    }
  ]

  secrets = [
    for name in sort(keys(var.container_secrets)) : {
      name      = name
      valueFrom = var.container_secrets[name]
    }
  ]
}

# ---------------------------------------------------------------------------
# ECR (toggle) — per-engagement repository in the CUSTOMER account; the
# operator copies the CI-built digest in (design §A6). Default AES256
# encryption: the engagement-CMK scope (§A9) deliberately excludes ECR.
# ---------------------------------------------------------------------------

resource "aws_ecr_repository" "app" {
  count = var.create_ecr_repo ? 1 : 0

  # force_delete deliberately ABSENT: a non-empty repo blocking destroy is a
  # teardown gate, not a bug. The pre-destroy sequence (delete images first)
  # is in docs/deploy.md — Teardown.
  name                 = "${var.name_prefix}-substrate"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  count = var.create_ecr_repo ? 1 : 0

  repository = aws_ecr_repository.app[0].name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the last 10 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = { type = "expire" }
      }
    ]
  })
}

# ---------------------------------------------------------------------------
# IAM — execution role (task launch: pull image, fetch secrets, write logs)
# and task role (runtime: empty baseline + opt-in EFS / ECS Exec statements).
# ---------------------------------------------------------------------------

locals {
  ecs_tasks_assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-task-exec"
  description        = "ECS task execution role — reads the runtime secrets at task launch (the deploy role's GetSecretValue deny applies to provisioning, not to this runtime path)"
  assume_role_policy = local.ecs_tasks_assume_role_policy
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "runtime-secrets"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadRuntimeSecrets"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = sort(values(var.container_secrets))
      },
      {
        Sid      = "DecryptWithEngagementCmk"
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = var.kms_key_arn
      }
    ]
  })
}

resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-task"
  description        = "ECS task role for substrate-app — empty baseline; EFS and ECS Exec statements attach only when those toggles are on"
  assume_role_policy = local.ecs_tasks_assume_role_policy
}

resource "aws_iam_role_policy" "task_execute_command" {
  count = var.enable_execute_command ? 1 : 0

  name = "ecs-exec-ssmmessages"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcsExecChannel"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy" "task_efs" {
  count = var.enable_persistent_storage ? 1 : 0

  name = "efs-access"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "MountEngagementEfs"
        Effect = "Allow"
        Action = [
          "elasticfilesystem:ClientMount",
          "elasticfilesystem:ClientWrite"
        ]
        Resource = aws_efs_file_system.app[0].arn
        Condition = {
          StringEquals = {
            "elasticfilesystem:AccessPointArn" = [for ap in aws_efs_access_point.app : ap.arn]
          }
        }
      }
    ]
  })
}

# ---------------------------------------------------------------------------
# EFS (toggle) — encrypted with the engagement CMK, TLS-only mounts, IAM auth,
# access points pinned to uid/gid 1001 (the Dockerfile's substrate user).
# ---------------------------------------------------------------------------

resource "aws_efs_file_system" "app" {
  count = var.enable_persistent_storage ? 1 : 0

  encrypted  = true
  kms_key_id = var.kms_key_arn

  lifecycle_policy {
    transition_to_ia = "AFTER_30_DAYS"
  }

  tags = { Name = "${var.name_prefix}-efs" }
}

# Filesystem-ENFORCED posture, not just client-side: transit_encryption on the
# task-def volume is the client opting in; this policy makes TLS and IAM
# authorization mandatory for ANY mount (e.g. an ad-hoc operator mount target
# or a misconfigured future task). Attaching a policy also removes the EFS
# default allow-any-network-client behavior — only the task role, via the
# engagement mount targets, is allowed.
resource "aws_efs_file_system_policy" "app" {
  count = var.enable_persistent_storage ? 1 : 0

  file_system_id = aws_efs_file_system.app[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowTaskRoleViaMountTargets"
        Effect    = "Allow"
        Principal = { AWS = aws_iam_role.task.arn }
        Action = [
          "elasticfilesystem:ClientMount",
          "elasticfilesystem:ClientWrite"
        ]
        Resource = aws_efs_file_system.app[0].arn
        Condition = {
          Bool = { "elasticfilesystem:AccessedViaMountTarget" = "true" }
        }
      },
      {
        Sid       = "DenyNonTlsClients"
        Effect    = "Deny"
        Principal = { AWS = "*" }
        Action    = "elasticfilesystem:Client*"
        Resource  = aws_efs_file_system.app[0].arn
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

resource "aws_efs_mount_target" "app" {
  count = var.enable_persistent_storage ? length(var.app_subnet_ids) : 0

  file_system_id  = aws_efs_file_system.app[0].id
  subnet_id       = var.app_subnet_ids[count.index]
  security_groups = [var.efs_security_group_id]
}

resource "aws_efs_access_point" "app" {
  for_each = var.enable_persistent_storage ? local.efs_paths : {}

  file_system_id = aws_efs_file_system.app[0].id

  posix_user {
    uid = 1001
    gid = 1001
  }

  root_directory {
    path = each.value

    creation_info {
      owner_uid   = 1001
      owner_gid   = 1001
      permissions = "750"
    }
  }

  tags = { Name = "${var.name_prefix}-efs-${each.key}" }
}

# ---------------------------------------------------------------------------
# ECS cluster + task definition
# ---------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = "${var.name_prefix}-substrate-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_cpu)
  memory                   = tostring(var.task_memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  # readonlyRootFilesystem deliberately NOT set: the app writes its filesystem
  # roots under /opt/substrate (port-fleet coordination flag — revisit when
  # uploads move to S3-backed storage).
  container_definitions = jsonencode([
    {
      name      = local.container_name
      image     = var.image_uri
      essential = true

      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]

      environment = local.environment
      secrets     = local.secrets
      mountPoints = local.mount_points

      # wget ships in the eclipse-temurin runtime image the Dockerfile builds on.
      healthCheck = {
        command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 120
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "substrate-app"
        }
      }
    }
  ])

  dynamic "volume" {
    for_each = var.enable_persistent_storage ? local.efs_paths : {}

    content {
      name = volume.key

      efs_volume_configuration {
        file_system_id     = aws_efs_file_system.app[0].id
        transit_encryption = "ENABLED"

        authorization_config {
          access_point_id = aws_efs_access_point.app[volume.key].id
          iam             = "ENABLED"
        }
      }
    }
  }
}

# ---------------------------------------------------------------------------
# ALB — TLS 1.2+/1.3 termination, /actuator/health target checks.
# ---------------------------------------------------------------------------

resource "aws_lb" "app" {
  name               = var.alb_name
  load_balancer_type = "application"
  internal           = var.alb_internal
  security_groups    = [var.alb_security_group_id]
  subnets            = var.alb_subnet_ids

  drop_invalid_header_fields = true
  # The app's LLM calls run up to 120s (substrate.llm timeout-ms) — keep the
  # ALB idle timeout comfortably above that.
  idle_timeout = 180

  dynamic "access_logs" {
    for_each = var.enable_alb_access_logs ? [1] : []

    content {
      bucket  = var.access_logs_bucket
      prefix  = "alb"
      enabled = true
    }
  }
}

resource "aws_lb_target_group" "app" {
  name        = var.target_group_name
  vpc_id      = var.vpc_id
  target_type = "ip"
  port        = 8080
  protocol    = "HTTP"

  deregistration_delay = 30

  health_check {
    path                = "/actuator/health"
    protocol            = "HTTP"
    matcher             = "200"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.app.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# Public mode only: redirect plain HTTP to HTTPS.
resource "aws_lb_listener" "http_redirect" {
  count = var.alb_internal ? 0 : 1

  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# ---------------------------------------------------------------------------
# ECS service
# ---------------------------------------------------------------------------

resource "aws_ecs_service" "app" {
  name            = "${var.name_prefix}-substrate-app"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  enable_execute_command  = var.enable_execute_command
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  # Spring Boot start + Flyway migrations before /actuator/health goes green.
  health_check_grace_period_seconds = var.health_check_grace_period_seconds

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.app_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = local.container_name
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.https,
    aws_efs_mount_target.app,
    # Tasks must never mount before the TLS/IAM-enforcing policy is attached.
    aws_efs_file_system_policy.app,
  ]
}
