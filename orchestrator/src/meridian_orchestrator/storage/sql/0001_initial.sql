-- Initial schema (M1).
--
-- _schema_version is bootstrapped by the migration runner outside any migration.
-- Task planning columns (estimated_tokens, actual_tokens) ship in 0002_task_planning.sql at M4.
-- pm_mode_log table ships in 0003_pm_mode.sql at M5.
-- Notification tables (inbound_messages, outbound_messages) ship in 0004_notifications.sql at M6.

CREATE TABLE companies (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    config_json TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE projects (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    name TEXT NOT NULL,
    working_directory TEXT,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(company_id, name)
);

CREATE TABLE agents (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    role TEXT NOT NULL,
    config_path TEXT NOT NULL,
    reports_to TEXT REFERENCES agents(id),
    status TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_agents_company_role ON agents(company_id, role);

CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    project_id TEXT NOT NULL REFERENCES projects(id),
    parent_task_id TEXT REFERENCES tasks(id),
    agent_role TEXT NOT NULL,
    description TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 2,
    complexity INTEGER NOT NULL DEFAULT 3,
    status TEXT NOT NULL DEFAULT 'pending',
    blocking_human INTEGER NOT NULL DEFAULT 0,
    submitted_by TEXT,
    claimed_by TEXT,
    claimed_at TEXT,
    completed_at TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX idx_tasks_agent_role ON tasks(agent_role);
CREATE INDEX idx_tasks_parent ON tasks(parent_task_id);

CREATE TABLE api_usage (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    agent_id TEXT REFERENCES agents(id),
    model TEXT NOT NULL,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    cost_usd REAL,
    task_id TEXT REFERENCES tasks(id),
    window_start TEXT NOT NULL
);

CREATE INDEX idx_api_usage_window ON api_usage(window_start);
CREATE INDEX idx_api_usage_agent ON api_usage(agent_id);

CREATE TABLE audit_log (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    details_json TEXT,
    success INTEGER NOT NULL DEFAULT 1,
    error_message TEXT
);

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_actor ON audit_log(actor);
