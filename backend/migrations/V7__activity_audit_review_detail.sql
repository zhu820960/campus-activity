-- REVIEW ONLY: do not execute without confirming campus_activity_miniapp_dev,
-- inspecting the existing column and index definitions, a verified full backup,
-- an isolated rehearsal, and explicit approval for this exact ALTER TABLE.
USE `campus_activity_miniapp_dev`;

ALTER TABLE `activity_audits`
  ADD COLUMN `detail` VARCHAR(500) NULL AFTER `created_at`;

-- Historic review reasons were not stored per audit event. Keep them NULL;
-- never copy the activity's current review_note into older audit rows.
-- Physical rollback after new reviews exist requires separate approval.
