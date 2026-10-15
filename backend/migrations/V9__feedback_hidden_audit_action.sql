-- REVIEW ONLY: V2-V8 approval does not authorize this migration.
-- The current MySQL ENUM rejects ActivityAudit.Action.FEEDBACK_HIDDEN.
-- Before execution, confirm the target schema and existing action values,
-- back up the current database, rehearse on an isolated MySQL copy, and
-- obtain explicit approval for this exact ALTER TABLE.
USE `campus_activity_miniapp_dev`;

ALTER TABLE `activity_audits`
  MODIFY COLUMN `action` ENUM(
    'CREATED', 'DIRECT_PUBLISHED', 'EDITED', 'QR_ISSUED',
    'REVIEW_APPROVED', 'REVIEW_REJECTED', 'FEEDBACK_HIDDEN'
  ) NOT NULL;

-- Existing audit rows and IDs are preserved. Do not remove FEEDBACK_HIDDEN
-- from the ENUM after that action has been written.
