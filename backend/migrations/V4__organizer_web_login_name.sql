-- REVIEW ONLY: do not execute without target verification, backup,
-- existing-column/index inspection and explicit approval.
USE `campus_activity_miniapp_dev`;

ALTER TABLE `user_accounts`
  ADD COLUMN `login_name` VARCHAR(32) NULL AFTER `student_number`,
  ADD CONSTRAINT `uk_user_login_name` UNIQUE (`login_name`);

-- Existing Web users keep email/password login; existing student rows remain unchanged.
-- No automatic ADMIN username or password rewrite. Revert application before any
-- physical rollback; never drop the column once names have been activated.
