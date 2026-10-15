-- Rehearsal only. V9 is intentionally excluded.
ALTER TABLE `activities` ADD COLUMN `cancel_reason` VARCHAR(500) NULL;

CREATE TABLE `activity_change_requests` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `activity_id` BIGINT NOT NULL,
  `requester_id` BIGINT NOT NULL,
  `request_type` VARCHAR(12) NOT NULL,
  `reason` VARCHAR(1000) NOT NULL,
  `base_snapshot` JSON NOT NULL,
  `proposed_snapshot` JSON NULL,
  `status` VARCHAR(12) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `reviewed_at` DATETIME(6) NULL,
  `reviewer_id` BIGINT NULL,
  `review_note` VARCHAR(1000) NULL,
  `pending_activity_id` BIGINT
    GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING' THEN `activity_id` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_change_one_pending_activity` (`pending_activity_id`),
  KEY `idx_change_status_created` (`status`, `created_at`),
  CONSTRAINT `fk_change_activity` FOREIGN KEY (`activity_id`) REFERENCES `activities` (`id`),
  CONSTRAINT `fk_change_requester` FOREIGN KEY (`requester_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_change_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `ck_change_type` CHECK (`request_type` IN ('CHANGE', 'CANCEL')),
  CONSTRAINT `ck_change_status` CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `activity_mutation_audits` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `activity_id` BIGINT NOT NULL,
  `actor_id` BIGINT NOT NULL,
  `change_request_id` BIGINT NULL,
  `action` VARCHAR(32) NOT NULL,
  `before_snapshot` JSON NOT NULL,
  `after_snapshot` JSON NOT NULL,
  `reason` VARCHAR(1000) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_mutation_activity_created` (`activity_id`, `created_at`),
  CONSTRAINT `fk_mutation_activity` FOREIGN KEY (`activity_id`) REFERENCES `activities` (`id`),
  CONSTRAINT `fk_mutation_actor` FOREIGN KEY (`actor_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_mutation_request` FOREIGN KEY (`change_request_id`) REFERENCES `activity_change_requests` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `participation_export_audits` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NOT NULL,
  `scope` VARCHAR(20) NOT NULL,
  `activity_id` BIGINT NULL,
  `filters_json` JSON NOT NULL,
  `format` VARCHAR(8) NOT NULL,
  `row_count` INT NOT NULL,
  `outcome` VARCHAR(20) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_export_actor_created` (`actor_id`, `created_at`),
  CONSTRAINT `fk_export_actor` FOREIGN KEY (`actor_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_export_activity` FOREIGN KEY (`activity_id`) REFERENCES `activities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
