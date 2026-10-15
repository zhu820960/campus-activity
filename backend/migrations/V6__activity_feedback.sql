-- REVIEW ONLY: do not execute without target verification, backup and explicit approval.
USE `campus_activity_miniapp_dev`;

CREATE TABLE `activity_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `activity_id` BIGINT NOT NULL,
  `student_id` BIGINT NOT NULL,
  `rating` INT NOT NULL,
  `comment` VARCHAR(1000) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  `hidden_at` DATETIME(6) NULL,
  `moderator_id` BIGINT NULL,
  `hidden_reason` VARCHAR(300) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_activity_student` (`activity_id`, `student_id`),
  KEY `idx_feedback_activity_created` (`activity_id`, `created_at`),
  KEY `idx_feedback_student` (`student_id`),
  KEY `idx_feedback_moderator` (`moderator_id`),
  CONSTRAINT `fk_feedback_activity` FOREIGN KEY (`activity_id`) REFERENCES `activities` (`id`),
  CONSTRAINT `fk_feedback_student` FOREIGN KEY (`student_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_feedback_moderator` FOREIGN KEY (`moderator_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `ck_feedback_rating` CHECK (`rating` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing activities and attendance remain untouched. Do not DROP after feedback exists.
