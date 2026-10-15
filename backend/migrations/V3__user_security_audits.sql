-- REVIEW ONLY: do not execute without target verification, full backup,
-- explicit approval, and confirmation of current migration history.
USE `campus_activity_miniapp_dev`;

CREATE TABLE `user_security_audits` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `target_id` BIGINT NOT NULL,
  `action` VARCHAR(40) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_security_audits_actor` (`actor_id`),
  KEY `idx_user_security_audits_target` (`target_id`),
  KEY `idx_user_security_audits_created` (`created_at`),
  CONSTRAINT `fk_user_security_audits_actor` FOREIGN KEY (`actor_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_user_security_audits_target` FOREIGN KEY (`target_id`) REFERENCES `user_accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Rollback is application-first. Do not DROP this audit table after it contains
-- records; retain history and separately approve any physical rollback.
