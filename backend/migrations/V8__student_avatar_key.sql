-- REVIEW ONLY. Do not execute before the specifically approved MySQL migration window.
-- Apply only to campus_activity_miniapp_dev after a verified backup and V2-V7 review.
USE `campus_activity_miniapp_dev`;

ALTER TABLE `user_accounts`
  ADD COLUMN `avatar_key` VARCHAR(40) NULL;

-- Existing users keep NULL and display the default initial avatar.
-- Application rollback: deploy the previous code while retaining this nullable column.
-- Do not drop this column or avatar files once users have uploaded images.
