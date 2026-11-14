# AGENTS.md

## CI

Development CI runs only by explicit dispatch, one run at a time (owner, 2026-11-14).
Only the leader dispatches ci.yml. Batch suites and changes into one run. The dev-ci
group lets a running run finish and replaces a queued run with the newest dispatch.
Release, tag, publishing and Pages triggers remain.
