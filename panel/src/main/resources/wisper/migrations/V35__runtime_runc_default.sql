-- The security model changed: runc is now the default runtime, not an escape hatch.
-- The isolation that gVisor provided is replaced by a custom seccomp allow-list,
-- dropped capabilities, no-new-privileges, namespace isolation, egress filtering,
-- OOM score adjustment and cgroups v2 ceilings — all in sasayaki's hardening.go.
--
-- The CHECK constraint `service_runc_needs_reason` required a non-blank
-- isolation_reason whenever runtime_isolation = 'RUNC', which made RUNC
-- deliberately awkward to choose. That constraint is no longer appropriate:
-- RUNC is the default, and RUNSC is the option that needs a reason.
--
-- The constraint is dropped in two steps because PostgreSQL does not support
-- IF EXISTS on DROP CONSTRAINT before version 9.

ALTER TABLE service DROP CONSTRAINT IF EXISTS service_runc_needs_reason;

-- The default changes from RUNSC to RUNC. Existing services keep whatever they
-- were created with — this only affects new inserts.
ALTER TABLE service ALTER COLUMN runtime_isolation SET DEFAULT 'RUNC';
