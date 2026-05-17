# tests/integration/auth/ — directory conventions

This directory is FLAT — no `__init__.py` placeholder. Reason: pytest's package
import mode resolves `tests/unit/auth/__init__.py` (added at Slice 1a commit 1
9233d68) and `tests/integration/auth/__init__.py` as the same top-level
package `auth`. The collision is silent in pytest's collection logs but causes
the second-loaded subdir to shadow the first, dropping tests from the
discovery set. Slice 1a commit 4 (c7924ff) established the convention of
omitting `__init__.py` in this subdir. Future test additions to
`tests/integration/auth/` should follow the convention.
