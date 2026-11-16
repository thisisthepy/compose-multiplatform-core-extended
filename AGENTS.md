# AGENTS.md

## CI

Development CI runs only by explicit dispatch, one run at a time (owner, 2026-11-14).
Only the leader dispatches ci.yml. Batch suites and changes into one run. Its `suites` input
is `all` (the default) or a comma separated list of ci.yml's job names, so a check of one
platform does not take every runner; a name that is not a job fails the run. The dev-ci
group lets a running run finish and replaces a queued run with the newest dispatch.
Release, tag, publishing and Pages triggers remain.
