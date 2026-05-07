"""Service configuration loaded from environment via pydantic-settings."""

from __future__ import annotations

from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_data_dir() -> Path:
    return Path.home() / ".meridian-orchestrator"


class Settings(BaseSettings):
    """Service-level configuration.

    Service-level fields use the ``MERIDIAN_ORCH_`` env prefix. External-credential
    fields added in later milestones (Telegram tokens at M6, etc.) read with their
    natural names via per-field aliases.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="MERIDIAN_ORCH_",
        extra="ignore",
    )

    data_dir: Path = Field(default_factory=_default_data_dir)
    log_level: str = Field(default="INFO")

    mcp_host: str = Field(default="127.0.0.1")
    mcp_port: int = Field(default=8765, ge=1, le=65535)
    mcp_allow_non_loopback: bool = Field(default=False)

    # M4c-engineer-supervisor: ships dormant. Flag flip is M5's behavioral
    # milestone (alongside PMSupervisor's analogous flag). See
    # `project_m5_engineer_supervisor_flag_flip` memory for the M5
    # verification checklist (subscription_5h attribution, compaction-at-80%,
    # heartbeat calibration).
    enable_engineer_supervisor: bool = Field(default=False)

    # M5-pm-supervisor-base: ships dormant. Flag flip is M5-flag-flips
    # alongside enable_engineer_supervisor. PMSupervisor doesn't need a
    # substrate working tree (no canonical-clone analog) — the
    # substrate_working_directory validator above is engineer-only.
    enable_pm_supervisor: bool = Field(default=False)

    # M5-pm-mode-and-window-state commit 7: OAuth subscription token
    # the daemon uses to poll Anthropic's /api/oauth/usage endpoint.
    #
    # **First instance of the external-credential-with-validation-alias
    # convention** documented in the class docstring above. Future
    # external-credential fields (Telegram bot token at M6, etc.)
    # should follow this exact shape:
    # - Field name matches the env var (snake_case lowercase of the
    #   env var name) — searchability beats brevity.
    # - Type is ``str | None``.
    # - Default is ``None`` (absence triggers a skip-with-warning code
    #   path; see main.py's lifespan handling for the OAuth token).
    # - ``validation_alias`` names the env var explicitly so the
    #   ``MERIDIAN_ORCH_`` prefix isn't applied (Pydantic-settings
    #   default behavior would otherwise look for
    #   ``MERIDIAN_ORCH_CLAUDE_CODE_OAUTH_TOKEN``).
    #
    # **Footgun for tests** (caught at M5-pm-mode-and-window-state
    # commit 7 during test development): pydantic-settings'
    # ``populate_by_name`` defaults to ``False`` for fields with
    # ``validation_alias`` set. This means constructor kwargs by
    # Python field name are silently ignored; only env var
    # population works. ``Settings(claude_code_oauth_token="test")``
    # in a test will produce a Settings instance with
    # ``claude_code_oauth_token=None`` — no error, no warning, just
    # the default value. Tests for fields using this convention must
    # use ``monkeypatch.setenv(env_var_name, value)`` BEFORE
    # ``Settings()`` construction, plus a sanity assertion that the
    # field populated correctly. Reference pattern:
    # ``tests/integration/test_daemon_lifecycle.py::_make_settings_for_full_lifespan``
    # uses ``monkeypatch.setenv("CLAUDE_CODE_OAUTH_TOKEN", oauth_token)``
    # followed by ``assert settings.claude_code_oauth_token ==
    # "test-oauth-token"`` to surface this if a future Pydantic-
    # settings config change ever silently breaks the alias path.
    #
    # When the token is unset, the OAuth usage poller is skipped at
    # daemon startup with an INFO log naming the consequence; the
    # daemon still serves MCP and runs supervisors normally, but
    # ``get_window_state`` returns the cold-start placeholder
    # (``pace_recommendation='unknown'``) for all queries.
    claude_code_oauth_token: str | None = Field(
        default=None,
        validation_alias="CLAUDE_CODE_OAUTH_TOKEN",
    )

    # M5-wrapper-closure: the substrate working tree the daemon discovers
    # ``origin`` from at canonical-provisioning time. Outside ``data_dir``
    # because it points at Mike's actual local clone, not daemon state.
    # No default — daemon fails fast at provisioning if unset and the
    # supervisor flag is on (validator below).
    substrate_working_directory: Path | None = Field(default=None)

    # M5-continuous-pm-driver commit 4: random-audit-sampling fraction
    # consumed by audit/sampler.py:Sampler. Coin-flip per record_sampleable_event
    # call with this probability; selected events land in audit_sample_log
    # with re_eval_state='pending' for the future async re-evaluator
    # (deferred to post-M5-flag-flips focused-observability sub-milestone
    # per F5.6.C disposition).
    #
    # Default 0.0 (sampling disabled) per F5.2 Q9 Reading A — opt-in
    # shape mirroring enable_engineer_supervisor / enable_pm_supervisor
    # flag conventions. Operator sets a non-zero value to enable
    # sampling.
    #
    # Phase 1 ships single global percentage; per-event-kind override
    # (e.g., higher percentage on contract_violation than on
    # spin_rationale) deferred to a future commit per F5.2 Q4 Reading C
    # when real-session data shows per-kind tuning is warranted.
    audit_sample_percentage: float = Field(default=0.0, ge=0.0, le=1.0)

    # M5-continuous-pm-driver commit 7: tick cadence for the continuous
    # PM loop's `wait_for_next_tick` MCP tool. PM's continuous-mode
    # subprocess calls wait_for_next_tick at end of each iteration;
    # server-side handler sleeps this many seconds then returns.
    #
    # Default 60.0 seconds per F8.2 Q2-B disposition (conservative-
    # then-tune-down framing; cadence-volume carry-forward from
    # commits 3-4 holds with margin under 60s baseline). Operator
    # may tune via env var: tighter for responsiveness (down to ~5s),
    # looser for cost-conscious operation (up to ~300s / 5min).
    #
    # Range guard: ge=1.0 (sub-second tick risks runaway loop;
    # measurable cost overhead per MCP call; PM's prompt-driven
    # decision logic doesn't react meaningfully at sub-second
    # cadence). le=3600.0 (one-hour ceiling; longer is effectively
    # off-by-default and operator should disable via the
    # enable_pm_supervisor flag instead of pinning a useless cadence).
    pm_loop_tick_seconds: float = Field(default=60.0, ge=1.0, le=3600.0)

    @model_validator(mode="after")
    def _require_substrate_working_directory_when_supervisor_enabled(
        self,
    ) -> Settings:
        """Conditional fail-fast: substrate_working_directory is only
        required when the engineer-supervisor flag is on (canonical
        provisioning needs it). M5-wrapper-closure tests run with the
        supervisor off and don't need the env var."""
        if self.enable_engineer_supervisor and self.substrate_working_directory is None:
            raise ValueError(
                "MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY must be set when "
                "MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true; canonical-clone "
                "provisioning reads `origin` from this path"
            )
        return self

    @property
    def db_path(self) -> Path:
        return self.data_dir / "data" / "orchestrator.db"

    @property
    def logs_dir(self) -> Path:
        return self.data_dir / "logs"

    @property
    def backups_dir(self) -> Path:
        return self.data_dir / "backups"

    @property
    def knowledge_dir(self) -> Path:
        return self.data_dir / "knowledge"

    @property
    def config_dir(self) -> Path:
        return self.data_dir / "config"

    @property
    def agents_dir(self) -> Path:
        return self.data_dir / "config" / "agents"

    @property
    def engineer_supervisor_dir(self) -> Path:
        """The engineer-supervisor's subprocess cwd. Per spec
        (`11-engineer-bridge.md` § Long-running supervisors): no git, no
        worktree — sub-agents handle code in dispatch worktrees off the
        canonical clone. This is a separate dedicated directory."""
        return self.data_dir / "engineer"

    @property
    def pm_continuous_dir(self) -> Path:
        """The PM continuous-mode subprocess cwd. Per spec
        (`11-engineer-bridge.md` § Long-running supervisors): no git, no
        worktree — PM's writes land via MCP tools (knowledge writes,
        task notes), not filesystem. Symmetric with
        engineer_supervisor_dir."""
        return self.data_dir / "pm-continuous"

    @property
    def substrate_canonical_dir(self) -> Path:
        """Daemon-owned canonical clone path. The dispatcher's
        BridgeDispatcher reads/writes here; per-dispatch worktrees branch
        from this clone. See `bridge/canonical.py` § CanonicalClone."""
        return self.data_dir / "substrate-canonical"

    @property
    def dispatch_worktrees_root(self) -> Path:
        """Root directory for per-dispatch worktrees. Each dispatch gets a
        subdirectory under this root, cleaned up on terminal state unless
        ``keep_worktree_on_failure`` is set."""
        return self.data_dir / "dispatch-worktrees"
