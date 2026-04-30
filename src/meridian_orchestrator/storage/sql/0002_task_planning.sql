-- Task planning columns for window-aware dispatch sizing (M4).
--
-- estimated_tokens is set by PM at submit time and consumed by the bridge
-- timeout calculation: hard_timeout_seconds = max(estimated_tokens / tps, 300).
-- actual_tokens is filled on `update_task_status('completed', actual_tokens=...)`
-- so the planner can refine its estimates over time.

ALTER TABLE tasks ADD COLUMN estimated_tokens INTEGER;
ALTER TABLE tasks ADD COLUMN actual_tokens INTEGER;
