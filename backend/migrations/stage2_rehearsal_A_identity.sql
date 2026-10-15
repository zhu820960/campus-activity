-- Rehearsal only. The caller must verify that DATABASE() is a stage2_rehearsal clone.
CREATE TABLE `wechat_identities` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `app_id` VARCHAR(32) NOT NULL,
  `open_id` VARCHAR(128) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wechat_identity_app_openid` (`app_id`, `open_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `wechat_student_bindings`
  ADD COLUMN `identity_id` BIGINT NULL,
  ADD COLUMN `unbound_at` DATETIME(6) NULL,
  ADD COLUMN `unbound_by` BIGINT NULL,
  ADD COLUMN `unbound_reason` VARCHAR(500) NULL,
  ADD KEY `idx_binding_identity` (`identity_id`),
  ADD CONSTRAINT `fk_binding_unbound_by` FOREIGN KEY (`unbound_by`) REFERENCES `user_accounts` (`id`);

INSERT INTO `wechat_identities` (`app_id`, `open_id`, `created_at`)
SELECT `app_id`, `open_id`, MIN(`created_at`)
FROM `wechat_student_bindings`
GROUP BY `app_id`, `open_id`;

UPDATE `wechat_student_bindings` b
JOIN `wechat_identities` i ON i.`app_id` = b.`app_id` AND i.`open_id` = b.`open_id`
SET b.`identity_id` = i.`id`
WHERE b.`identity_id` IS NULL;

ALTER TABLE `wechat_student_bindings`
  MODIFY COLUMN `identity_id` BIGINT NOT NULL,
  ADD CONSTRAINT `fk_binding_identity` FOREIGN KEY (`identity_id`) REFERENCES `wechat_identities` (`id`),
  ADD COLUMN `active_open_id` VARCHAR(128)
    GENERATED ALWAYS AS (CASE WHEN `unbound_at` IS NULL THEN `open_id` ELSE NULL END) STORED,
  ADD COLUMN `active_student_id` BIGINT
    GENERATED ALWAYS AS (CASE WHEN `unbound_at` IS NULL THEN `student_id` ELSE NULL END) STORED,
  ADD UNIQUE KEY `uk_binding_active_openid` (`app_id`, `active_open_id`),
  ADD UNIQUE KEY `uk_binding_active_student` (`app_id`, `active_student_id`);

ALTER TABLE `wechat_student_bindings`
  DROP INDEX `uk_wechat_binding_app_openid`,
  DROP INDEX `uk_wechat_binding_app_student`;
