-- REVIEW ONLY: do not execute without a fresh development DB inventory, backup,
-- data-impact review, and explicit approval for campus_activity_miniapp_dev.
-- V1's binding and token tables, their constraints, and all existing rows remain intact.
USE `campus_activity_miniapp_dev`;

ALTER TABLE `user_accounts`
  ADD COLUMN `student_number` VARCHAR(11) NULL AFTER `name`,
  MODIFY COLUMN `email` VARCHAR(160) NULL,
  MODIFY COLUMN `password_hash` VARCHAR(255) NULL;

ALTER TABLE `user_accounts`
  ADD CONSTRAINT `uk_user_student_number` UNIQUE (`student_number`),
  ADD CONSTRAINT `ck_user_student_number` CHECK
    (`student_number` IS NULL OR `student_number` REGEXP '^[0-9]{11}$');

-- Existing users intentionally retain NULL student_number until individually verified.
-- Rollback is application-level first: disable the new flow and restore the previous
-- application. Do not drop student_number or tighten NULL columns if new students
-- have registered; restore the verified pre-migration backup only after separately
-- accounting for all post-migration rows and obtaining approval.
