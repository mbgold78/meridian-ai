# CloudWatch-native observability baseline (design §A11): the app already emits
# structured JSON via logback, so awslogs ships it as-is; Container Insights is
# enabled on the cluster (service module); alarms fan out to one SNS topic; one
# dashboard per engagement. No Prometheus stack — /actuator/prometheus stays
# available for a future AMP integration.

data "aws_caller_identity" "current" {}

# ---------------------------------------------------------------------------
# App log group — created here, consumed by the service module's awslogs config.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "app" {
  name              = "/meridian/${var.engagement_id}/substrate-app"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

# ---------------------------------------------------------------------------
# Alarm topic
# ---------------------------------------------------------------------------

# SNS deliberately uses default encryption: the engagement-CMK scope (design
# §A9) excludes SNS, and CMK-encrypted topics need extra CloudWatch key-policy
# statements for no isolation gain inside an already-isolated account.
resource "aws_sns_topic" "alarms" {
  name = "${var.name_prefix}-alarms"
}

resource "aws_sns_topic_subscription" "alarm_email" {
  count = var.alarm_email != "" ? 1 : 0

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ---------------------------------------------------------------------------
# Alarms — ALB, ECS service, RDS. All publish to the engagement topic.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "alb_target_5xx" {
  alarm_name          = "${var.name_prefix}-alb-target-5xx"
  alarm_description   = "substrate-app returned >10 5xx responses in 5 minutes"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_hosts" {
  alarm_name          = "${var.name_prefix}-alb-unhealthy-hosts"
  alarm_description   = "At least one substrate-app target is failing ALB health checks"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    TargetGroup  = var.target_group_arn_suffix
    LoadBalancer = var.alb_arn_suffix
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_latency_p95" {
  alarm_name          = "${var.name_prefix}-alb-latency-p95"
  alarm_description   = "substrate-app p95 response time exceeded 5s"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu_high" {
  alarm_name          = "${var.name_prefix}-ecs-cpu-high"
  alarm_description   = "substrate-app service CPU above 85% for 15 minutes"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"

  dimensions = {
    ClusterName = var.cluster_name
    ServiceName = var.service_name
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory_high" {
  alarm_name          = "${var.name_prefix}-ecs-memory-high"
  alarm_description   = "substrate-app service memory above 90% for 15 minutes"
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 90
  comparison_operator = "GreaterThanThreshold"

  dimensions = {
    ClusterName = var.cluster_name
    ServiceName = var.service_name
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage_low" {
  alarm_name          = "${var.name_prefix}-rds-free-storage-low"
  alarm_description   = "Engagement database free storage below 10 GiB"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 10737418240 # 10 GiB in bytes
  comparison_operator = "LessThanThreshold"

  dimensions = {
    DBInstanceIdentifier = var.db_instance_identifier
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu_high" {
  alarm_name          = "${var.name_prefix}-rds-cpu-high"
  alarm_description   = "Engagement database CPU above 85%"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"

  dimensions = {
    DBInstanceIdentifier = var.db_instance_identifier
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_connections_high" {
  alarm_name          = "${var.name_prefix}-rds-connections-high"
  alarm_description   = "Engagement database connection count approaching the instance-class ceiling (threshold ~80% of class max)"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.db_connections_alarm_threshold
  comparison_operator = "GreaterThanThreshold"

  dimensions = {
    DBInstanceIdentifier = var.db_instance_identifier
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_dashboard" "engagement" {
  dashboard_name = "${var.name_prefix}-substrate"

  dashboard_body = templatefile("${path.module}/dashboard.json.tpl", {
    region                  = var.aws_region
    alb_arn_suffix          = var.alb_arn_suffix
    target_group_arn_suffix = var.target_group_arn_suffix
    cluster_name            = var.cluster_name
    service_name            = var.service_name
    db_identifier           = var.db_instance_identifier
    log_group_name          = aws_cloudwatch_log_group.app.name
  })
}

# ---------------------------------------------------------------------------
# ALB access-log bucket (toggle) — SSE-S3 only (ALB log delivery cannot use a
# CMK), public access blocked, 90-day expiry.
# ---------------------------------------------------------------------------

locals {
  alb_logs_bucket_name = "meridian-${var.engagement_id}-alblogs-${data.aws_caller_identity.current.account_id}"

  # Regional ELB log-delivery accounts for regions GA before August 2022 (the
  # deprecated aws_elb_service_account data source is deliberately not used).
  # Regions GA after August 2022 use the logdelivery.elasticloadbalancing
  # service-principal statement instead. Source: AWS ALB access-logs docs.
  elb_log_delivery_accounts = {
    us-east-1      = "127311923021"
    us-east-2      = "033677994240"
    us-west-1      = "027434742980"
    us-west-2      = "797873946194"
    ca-central-1   = "985666609251"
    eu-central-1   = "054676820928"
    eu-west-1      = "156460612806"
    eu-west-2      = "652711504416"
    eu-west-3      = "009996457667"
    eu-north-1     = "897822967062"
    eu-south-1     = "635631232127"
    ap-northeast-1 = "582318560864"
    ap-northeast-2 = "600734575887"
    ap-northeast-3 = "383597477331"
    ap-southeast-1 = "114774131450"
    ap-southeast-2 = "783225319266"
    ap-south-1     = "718504428378"
    ap-east-1      = "754344448648"
    af-south-1     = "098369216593"
    me-south-1     = "076674570225"
    sa-east-1      = "507241528517"
  }

  elb_log_delivery_account = lookup(local.elb_log_delivery_accounts, var.aws_region, null)
}

resource "aws_s3_bucket" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  # force_destroy deliberately ABSENT: a non-empty log bucket blocking destroy
  # is a teardown gate, not a bug. The pre-destroy sequence (empty the bucket
  # first) is in docs/deploy.md — Teardown.
  bucket = local.alb_logs_bucket_name
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = 90
    }
  }
}

resource "aws_s3_bucket_policy" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Sid       = "AlbLogDeliveryServicePrincipal"
          Effect    = "Allow"
          Principal = { Service = "logdelivery.elasticloadbalancing.amazonaws.com" }
          Action    = "s3:PutObject"
          Resource  = "${aws_s3_bucket.alb_logs[0].arn}/alb/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
          Condition = {
            StringEquals = {
              "aws:SourceAccount" = data.aws_caller_identity.current.account_id
            }
          }
        }
      ],
      local.elb_log_delivery_account != null ? [
        {
          Sid       = "AlbLogDeliveryRegionalAccount"
          Effect    = "Allow"
          Principal = { AWS = "arn:aws:iam::${local.elb_log_delivery_account}:root" }
          Action    = "s3:PutObject"
          Resource  = "${aws_s3_bucket.alb_logs[0].arn}/alb/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
        }
      ] : [],
      [
        {
          Sid       = "DenyInsecureTransport"
          Effect    = "Deny"
          Principal = "*"
          Action    = "s3:*"
          Resource = [
            aws_s3_bucket.alb_logs[0].arn,
            "${aws_s3_bucket.alb_logs[0].arn}/*"
          ]
          Condition = {
            Bool = { "aws:SecureTransport" = "false" }
          }
        }
      ]
    )
  })
}
