-- REVIEW ONLY: do not execute without target verification, backup and explicit approval.
USE `campus_activity_miniapp_dev`;

CREATE TABLE `user_messages` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `recipient_id` BIGINT NOT NULL,
  `activity_id` BIGINT NULL,
  `kind` VARCHAR(32) NOT NULL,
  `title` VARCHAR(160) NOT NULL,
  `body` VARCHAR(500) NOT NULL,
  `event_key` VARCHAR(120) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `read_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_event_key` (`event_key`),
  KEY `idx_message_recipient_created` (`recipient_id`, `created_at`),
  KEY `idx_message_activity` (`activity_id`),
  CONSTRAINT `fk_message_recipient` FOREIGN KEY (`recipient_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_message_activity` FOREIGN KEY (`activity_id`) REFERENCES `activities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- New personal messages begin when this version is enabled. Do not fabricate history.
-- Roll back application first; preserve message rows pending separate approval.
