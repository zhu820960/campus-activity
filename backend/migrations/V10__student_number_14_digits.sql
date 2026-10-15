-- REVIEW ONLY: do not run on the development database without explicit
-- approval for this exact change, a fresh backup, and an isolated rehearsal.
-- V2 introduced the 11-digit column/check; V9 is independent and may be pending.
USE `campus_activity_miniapp_dev`;

ALTER TABLE `user_accounts`
  DROP CHECK `ck_user_student_number`,
  MODIFY COLUMN `student_number` VARCHAR(14) NULL,
  ADD CONSTRAINT `ck_user_student_number` CHECK
    (`student_number` IS NULL OR `student_number` REGEXP '^[0-9]{14}$');

-- The existing unique index on student_number and all rows remain unchanged.
-- Stop if any existing non-NULL value is not already a 14-digit number.
