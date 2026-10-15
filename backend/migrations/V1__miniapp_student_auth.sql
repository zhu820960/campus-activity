-- Approved target only: campus_activity_miniapp_dev.
-- Preflight: confirm target, inspect existing tables, and back up the entire dev database.
-- This migration intentionally fails if either table already exists.
USE `campus_activity_miniapp_dev`;

CREATE TABLE `wechat_student_bindings` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `app_id` VARCHAR(32) NOT NULL,
  `open_id` VARCHAR(128) NOT NULL,
  `student_id` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_wechat_binding_app_openid` UNIQUE (`app_id`, `open_id`),
  CONSTRAINT `uk_wechat_binding_app_student` UNIQUE (`app_id`, `student_id`),
  CONSTRAINT `fk_wechat_binding_student` FOREIGN KEY (`student_id`) REFERENCES `user_accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `miniapp_access_tokens` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `binding_id` BIGINT NOT NULL,
  `token_hash` CHAR(64) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `expires_at` DATETIME(6) NOT NULL,
  `revoked_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_miniapp_access_token_hash` UNIQUE (`token_hash`),
  KEY `idx_miniapp_access_token_expires` (`expires_at`),
  KEY `idx_miniapp_access_token_binding` (`binding_id`),
  CONSTRAINT `fk_miniapp_access_token_binding` FOREIGN KEY (`binding_id`) REFERENCES `wechat_student_bindings` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
