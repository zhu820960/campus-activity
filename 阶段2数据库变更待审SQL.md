# 阶段 2 数据库变更待审 SQL

2026-10-13 21:22 更新：D 消息来源组已在原 A/B/C 成功的独立副本上受控扩展，并用当前后端包 JPA `validate` 通过；尚未从完整备份一次性重放 A/B/C/D，也未获真实开发库执行授权。下方“尚未副本演练”为此前状态。

更新：2026-10-13。**仅供审阅，不是开发库执行授权；下面的 DDL/DML 未在 `campus_activity_miniapp_dev` 执行。** 已在独立副本 `campus_activity_miniapp_stage2_rehearsal_20261013_113503` 完成 A/B/C 顺序演练；应用兼容与专项审批仍缺。V9 独立审批，V2–V8、V10 不重跑。复制执行本文件中的片段不构成批准。

2026-10-13 20:05 增补 D 组（尚未副本演练）：`user_messages` 新增可空 `identity_id`、`idx_message_identity_created` 索引和 `fk_message_identity` 外键，具体隔离脚本见 `backend/migrations/stage2_rehearsal_D_message_provenance.sql`。旧消息的当次微信来源不可从当前绑定反推，全部保持 NULL，不能自动展示给新绑定者；同微信改绑不同学号仍能读取自身已归属消息。下一次全新副本须按 A/B/C/D 顺序演练并用当前后端包作 JPA `validate`；现有 A/B/C 副本通过记录不覆盖此 D 组。

## 前置核对

2026-10-13 已在目标库只读运行 `backend/migrations/stage2_preflight_readonly.sql`：MySQL 8.0.46，绑定 2、报名 6、签到 2，现有绑定双唯一键与令牌外键仍在；`activities.status` 为含 `CANCELLED` 的 ENUM，`user_messages.kind` 为 `VARCHAR(32)`。聚合结果和校验和见《数据库待审批变更清单.md》。仍须逐表核对 `SHOW CREATE TABLE` 的完整列、索引、外键、字符集和本稿全部名称；本稿按现有 JPA 实体和 V1–V8/V10 脚本推定，**不把推定当作已完成的结构比对**。完整备份、校验和及隔离副本演练后，才可申请逐项执行。

## A. 只增不删的身份结构草案

以下语句必须按顺序在隔离副本试验。时间列采用与现有绑定相同的 UTC `DATETIME(6)` 约定。`open_id` 属敏感标识，不能出现在前端响应、普通日志或导出。

```sql
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
```

副本中必须验证 `COUNT(*)` 不变、`identity_id IS NULL` 为零、`b.app_id = i.app_id AND b.open_id = i.open_id` 全部成立。回填不得使用 `INSERT IGNORE` 或凭学号推断身份。生产执行前还须处理并发新绑定，安排停写窗口；上述 `INSERT` 在已部分执行的库上**不可盲目重跑**。

以下为**单独切换步骤**，不可与上面的增量步骤混作一次批准。它必须等新版应用支持“只查 `unbound_at IS NULL` 的绑定”、逐请求复查令牌对应绑定、撤销旧令牌以及所有兼容性测试之后，停止旧版写入再操作。先添加新约束并核对，再在同一停写窗口删除旧约束。旧代码的 `findByAppIdAndOpenId` 等单行查询在出现历史行后不再安全。

```sql
ALTER TABLE `wechat_student_bindings`
  MODIFY COLUMN `identity_id` BIGINT NOT NULL,
  ADD CONSTRAINT `fk_binding_identity` FOREIGN KEY (`identity_id`) REFERENCES `wechat_identities` (`id`),
  ADD COLUMN `active_open_id` VARCHAR(128)
    GENERATED ALWAYS AS (CASE WHEN `unbound_at` IS NULL THEN `open_id` ELSE NULL END) STORED,
  ADD COLUMN `active_student_id` BIGINT
    GENERATED ALWAYS AS (CASE WHEN `unbound_at` IS NULL THEN `student_id` ELSE NULL END) STORED,
  ADD UNIQUE KEY `uk_binding_active_openid` (`app_id`, `active_open_id`),
  ADD UNIQUE KEY `uk_binding_active_student` (`app_id`, `active_student_id`);

-- 仅在新索引已存在且验证后，另行执行：
ALTER TABLE `wechat_student_bindings`
  DROP INDEX `uk_wechat_binding_app_openid`,
  DROP INDEX `uk_wechat_binding_app_student`;
```

MySQL 唯一索引允许多个 NULL，因此已解绑行可留存，新绑定仍受同一 AppID 下唯一约束保护；此语义须在独立 MySQL 8 副本用“旧行解绑、同微信重绑、同学号重绑、双活冲突”四种用例证明。现有 `miniapp_access_tokens.binding_id` 外键始终保留。解绑不能 `DELETE` 绑定行。

## B. 参与来源与申诉结构草案

历史报名/签到的微信来源**保持 NULL**，绝不根据当前绑定批量回填。新报名和扫码在业务事务中写入当次身份及绑定；管理员人工补签可为 NULL 并保留操作人/原因。所有身份来源外键必须校验该绑定与身份一致，不能只相信传入 ID。学号已被占用的微信拿不到现有学生 Bearer；申诉必须用服务端微信 `code` 校验后的一次性挑战凭证，不能复用或放宽普通上传接口。

```sql
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
```

`participation_attribution_reviews` 当前以一条报名及其可选签到为审核单位；预检若发现无对应报名的签到，须先决定单独归属模型，不可丢弃。目标账号已有同活动报名或签到时，`TRANSFER` 必须拒绝并进入 `DISPUTED`，不删除唯一键，也不合并记录。`appeal_challenges` 只存随机凭证的哈希；服务端微信 `code` 换取的身份与凭证绑定，默认 10 分钟有效，仅可私有暂存一张经校验/去元数据的学生证，提交申诉时原子消费。过期未提交的暂存原图另行清理，已结案原图 30 天清理，审核结论保留。需在副本及接口测试中覆盖重放、错身份、过期、重复上传及清理；本稿没有授权开放上传接口。

## C. 已发布变更、取消与导出结构草案

`activities.status` 已支持 `CANCELLED`，但取消原因尚无独立列；新增可空列保留旧活动原值。新变更只存请求快照，管理员批准前不覆盖 `activities`；同活动最多一个待办请求使用生成列唯一键。快照 JSON 的具体字段和最大长度必须在实体与副本中复核。

```sql
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
```

消息类型当前 `UserMessage.Kind` 只有五类；真实库 `user_messages.kind` 是 `VARCHAR(32)`，新增活动变更/取消通知仍需设计应用枚举、既有消费者和消息去重的兼容变更；不能借 V9 的 `activity_audits.action` ENUM 顺手修改。消息 `event_key` 去重应包含变更请求 ID 和接收人 ID，审批与通知写入同一业务事务。

## 必须实测的失败与恢复点

1. 副本逐条执行并记录语句、前后行数、耗时和失败位置；`ALTER TABLE` 非整组可回滚。任一失败即停止，不在真实库盲目重试。
2. 覆盖未绑定微信用一次性申诉凭证上传/提交、重放和过期拒绝、暂存文件清理；旧令牌在解绑后失效、当前绑定查询只返一条、重新绑定原微信和原学号、两个有效绑定冲突、旧报名/签到来源 NULL 且不可错误展示。
3. 覆盖取消/修改审核中的原内容可见、同活动待办唯一、已开始活动冲突限制、通知去重、导出权限与中文表格公式注入。
4. 回退优先停新写入并切回兼容应用，保留新增表及新产生的数据；不直接 DROP，不用旧备份覆盖新报名/签到。物理回退另列影响并另行审批。

本稿尚缺：完整库备份 SHA-256、逐表 `SHOW CREATE TABLE` 比对、隔离副本演练结果、实体和接口实现、**新结构**的 MySQL `validate`、正式逐步执行编号及用户对每组变更的专项批准。只读预检已完成，不等于上述任何一项；因此阶段 2 未完成，管理员解绑和自动归属迁移仍不得启用。
