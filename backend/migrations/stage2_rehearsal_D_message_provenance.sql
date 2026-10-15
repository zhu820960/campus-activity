-- Rehearsal only. The caller must verify that DATABASE() is a stage2_rehearsal clone.
-- Existing messages have no trustworthy historical WeChat source and remain NULL.
ALTER TABLE `user_messages`
  ADD COLUMN `identity_id` BIGINT NULL,
  ADD KEY `idx_message_identity_created` (`identity_id`, `created_at`),
  ADD CONSTRAINT `fk_message_identity` FOREIGN KEY (`identity_id`) REFERENCES `wechat_identities` (`id`);
