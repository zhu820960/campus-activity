-- Rehearsal only. Historical participation identity columns remain NULL.
ALTER TABLE `registrations`
  ADD COLUMN `identity_id` BIGINT NULL,
  ADD COLUMN `binding_id` BIGINT NULL,
  ADD KEY `idx_registration_identity` (`identity_id`),
  ADD CONSTRAINT `fk_registration_identity` FOREIGN KEY (`identity_id`) REFERENCES `wechat_identities` (`id`),
  ADD CONSTRAINT `fk_registration_binding` FOREIGN KEY (`binding_id`) REFERENCES `wechat_student_bindings` (`id`);

ALTER TABLE `checkins`
  ADD COLUMN `identity_id` BIGINT NULL,
  ADD COLUMN `binding_id` BIGINT NULL,
  ADD KEY `idx_checkin_identity` (`identity_id`),
  ADD CONSTRAINT `fk_checkin_identity` FOREIGN KEY (`identity_id`) REFERENCES `wechat_identities` (`id`),
  ADD CONSTRAINT `fk_checkin_binding` FOREIGN KEY (`binding_id`) REFERENCES `wechat_student_bindings` (`id`);

CREATE TABLE `appeal_challenges` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `identity_id` BIGINT NOT NULL,
  `token_hash` CHAR(64) NOT NULL,
  `evidence_key` VARCHAR(120) NULL,
  `created_at` DATETIME(6) NOT NULL,
  `expires_at` DATETIME(6) NOT NULL,
  `evidence_uploaded_at` DATETIME(6) NULL,
  `consumed_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appeal_challenge_token` (`token_hash`),
  KEY `idx_appeal_challenge_identity_expiry` (`identity_id`, `expires_at`),
  CONSTRAINT `fk_appeal_challenge_identity` FOREIGN KEY (`identity_id`) REFERENCES `wechat_identities` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `binding_appeals` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `applicant_identity_id` BIGINT NOT NULL,
  `current_binding_id` BIGINT NULL,
  `appeal_type` VARCHAR(32) NOT NULL,
  `proposed_student_number` VARCHAR(14) NOT NULL,
  `reason` VARCHAR(1000) NOT NULL,
  `evidence_key` VARCHAR(120) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `pending_identity_id` BIGINT
    GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING' THEN `applicant_identity_id` ELSE NULL END) STORED,
  `created_at` DATETIME(6) NOT NULL,
  `reviewed_at` DATETIME(6) NULL,
  `reviewer_id` BIGINT NULL,
  `review_note` VARCHAR(1000) NULL,
  `evidence_deleted_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appeal_one_pending_identity` (`pending_identity_id`),
  KEY `idx_appeal_status_created` (`status`, `created_at`),
  KEY `idx_appeal_identity_created` (`applicant_identity_id`, `created_at`),
  CONSTRAINT `fk_appeal_identity` FOREIGN KEY (`applicant_identity_id`) REFERENCES `wechat_identities` (`id`),
  CONSTRAINT `fk_appeal_binding` FOREIGN KEY (`current_binding_id`) REFERENCES `wechat_student_bindings` (`id`),
  CONSTRAINT `fk_appeal_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `ck_appeal_student_number` CHECK (`proposed_student_number` REGEXP '^[0-9]{14}$'),
  CONSTRAINT `ck_appeal_type` CHECK (`appeal_type` IN ('STUDENT_NUMBER_OCCUPIED', 'WRONG_STUDENT_NUMBER')),
  CONSTRAINT `ck_appeal_status` CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED', 'DISPUTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `participation_attribution_reviews` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `appeal_id` BIGINT NOT NULL,
  `registration_id` BIGINT NOT NULL,
  `checkin_id` BIGINT NULL,
  `source_student_id` BIGINT NOT NULL,
  `target_student_id` BIGINT NOT NULL,
  `decision` VARCHAR(20) NOT NULL,
  `reason` VARCHAR(1000) NOT NULL,
  `reviewer_id` BIGINT NOT NULL,
  `reviewed_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attribution_appeal_registration` (`appeal_id`, `registration_id`),
  CONSTRAINT `fk_attribution_appeal` FOREIGN KEY (`appeal_id`) REFERENCES `binding_appeals` (`id`),
  CONSTRAINT `fk_attribution_registration` FOREIGN KEY (`registration_id`) REFERENCES `registrations` (`id`),
  CONSTRAINT `fk_attribution_checkin` FOREIGN KEY (`checkin_id`) REFERENCES `checkins` (`id`),
  CONSTRAINT `fk_attribution_source` FOREIGN KEY (`source_student_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_attribution_target` FOREIGN KEY (`target_student_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `fk_attribution_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `user_accounts` (`id`),
  CONSTRAINT `ck_attribution_decision` CHECK (`decision` IN ('TRANSFER', 'KEEP', 'DISPUTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
