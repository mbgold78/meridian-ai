"""Audit log primitives.

The :func:`write_audit` writer is the single state-changing entry point for
the ``audit_log`` table. Every state-changing tool and every load-bearing
bridge lifecycle event funnels through it so the audit chain is uniform.
"""

from meridian_orchestrator.audit.logger import write_audit

__all__ = ["write_audit"]
