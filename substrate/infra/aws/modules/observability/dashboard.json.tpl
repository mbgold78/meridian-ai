{
  "widgets": [
    {
      "type": "metric",
      "x": 0,
      "y": 0,
      "width": 12,
      "height": 6,
      "properties": {
        "title": "ALB — requests & 5xx",
        "region": "${region}",
        "view": "timeSeries",
        "stacked": false,
        "period": 300,
        "metrics": [
          ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", "${alb_arn_suffix}", { "stat": "Sum" }],
          ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", "${alb_arn_suffix}", { "stat": "Sum" }],
          ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", "${alb_arn_suffix}", { "stat": "Sum" }]
        ]
      }
    },
    {
      "type": "metric",
      "x": 12,
      "y": 0,
      "width": 12,
      "height": 6,
      "properties": {
        "title": "ALB — target response time",
        "region": "${region}",
        "view": "timeSeries",
        "stacked": false,
        "period": 300,
        "metrics": [
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", "${alb_arn_suffix}", { "stat": "p50", "label": "p50" }],
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", "${alb_arn_suffix}", { "stat": "p95", "label": "p95" }],
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", "${alb_arn_suffix}", { "stat": "p99", "label": "p99" }]
        ]
      }
    },
    {
      "type": "metric",
      "x": 0,
      "y": 6,
      "width": 8,
      "height": 6,
      "properties": {
        "title": "ALB — healthy / unhealthy targets",
        "region": "${region}",
        "view": "timeSeries",
        "stacked": false,
        "period": 60,
        "metrics": [
          ["AWS/ApplicationELB", "HealthyHostCount", "TargetGroup", "${target_group_arn_suffix}", "LoadBalancer", "${alb_arn_suffix}", { "stat": "Minimum" }],
          ["AWS/ApplicationELB", "UnHealthyHostCount", "TargetGroup", "${target_group_arn_suffix}", "LoadBalancer", "${alb_arn_suffix}", { "stat": "Maximum" }]
        ]
      }
    },
    {
      "type": "metric",
      "x": 8,
      "y": 6,
      "width": 8,
      "height": 6,
      "properties": {
        "title": "ECS — substrate-app cpu / memory",
        "region": "${region}",
        "view": "timeSeries",
        "stacked": false,
        "period": 300,
        "metrics": [
          ["AWS/ECS", "CPUUtilization", "ClusterName", "${cluster_name}", "ServiceName", "${service_name}", { "stat": "Average" }],
          ["AWS/ECS", "MemoryUtilization", "ClusterName", "${cluster_name}", "ServiceName", "${service_name}", { "stat": "Average" }]
        ]
      }
    },
    {
      "type": "metric",
      "x": 16,
      "y": 6,
      "width": 8,
      "height": 6,
      "properties": {
        "title": "RDS — cpu / connections",
        "region": "${region}",
        "view": "timeSeries",
        "stacked": false,
        "period": 300,
        "metrics": [
          ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", "${db_identifier}", { "stat": "Average" }],
          ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", "${db_identifier}", { "stat": "Average", "yAxis": "right" }]
        ]
      }
    },
    {
      "type": "metric",
      "x": 0,
      "y": 12,
      "width": 8,
      "height": 6,
      "properties": {
        "title": "RDS — free storage",
        "region": "${region}",
        "view": "timeSeries",
        "stacked": false,
        "period": 300,
        "metrics": [
          ["AWS/RDS", "FreeStorageSpace", "DBInstanceIdentifier", "${db_identifier}", { "stat": "Average" }]
        ]
      }
    },
    {
      "type": "log",
      "x": 8,
      "y": 12,
      "width": 16,
      "height": 6,
      "properties": {
        "title": "substrate-app — recent errors (structured JSON `level` field)",
        "region": "${region}",
        "view": "table",
        "query": "SOURCE '${log_group_name}' | fields @timestamp, level, logger, message | filter level = 'ERROR' | sort @timestamp desc | limit 50"
      }
    }
  ]
}
