# MySQL 数据库结构整理报告

核查日期：2026-10-10  
项目：高校活动报名及动态签到平台设计与实现  
结论：本机开发库的九张预期表均已存在，结构可通过当前 Spring Boot 的 JPA `validate` 校验。本轮没有缺失表，也没有确认可删除的冗余表；没有执行建表、改表、删表或数据迁移。数据库结构已具备后续小程序联调的基础条件，但库内没有测试账号、活动或报名数据，真实微信登录和完整业务链路尚未验收。

## 1. 目标与执行边界

- 通过真实 TCP 连接确认的 MySQL 实例：`127.0.0.1:3306`，MySQL `8.0.46`；连接时查询到的当前数据库为 `campus_activity_miniapp_dev`。服务器报告的主机名为 `Aoyebobi_Hai`。
- 只对该开发库执行元数据和行数查询，以及完整备份。启动验证使用 `backend/start-local-mysql.ps1 -Database campus_activity_miniapp_dev`，脚本显式设置开发库 JDBC URL；它的 `CREATE DATABASE IF NOT EXISTS` 对已存在的目标库没有建库效果。
- 未连接、复制或迁移演示库；未删除或改写任何账号、活动、报名、签到数据；未执行 `DROP TABLE`、`TRUNCATE` 或数据删除。
- 核对了项目进度、小程序转型清单、认证数据库变更方案、上一阶段实施报告、JPA 实体和 Repository、`backend/migrations/` 及 `application.yml`。此前文档中的完成情况以本次实际查询和启动结果复核。

## 2. 修改前备份

在结构检查和启动验证前，发现旧备份 `backups/campus_activity_miniapp_dev_before_auth_20261010.sql`；本轮另行生成完整备份：

| 项目 | 结果 |
| --- | --- |
| 文件 | `backups/campus_activity_miniapp_dev_before_structure_review_20261010_213306.sql` |
| 完成时间 | 2026-10-10 21:33:31 +08:00 |
| 大小 | 11,606 字节 |
| SHA-256 | `BF22CD3C5C3E6028315A383DB6BDE30F59CB872527E011C8AFFCA35088FBC6A9` |
| 方式 | `mysqldump --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces --databases campus_activity_miniapp_dev` |
| 验证 | 命令退出码 0；文件可读，含九条 `CREATE TABLE`；九张表当前均为空，备份无业务数据插入语句 |

备份位于项目的 `backups/` 目录，该目录已被 `.gitignore` 忽略，不提交到仓库。未删除旧备份。恢复方案：如后续迁移发生错误，先停止服务、核对目标库和备份校验和，再由人工审查是否在独立开发库导入此备份；本轮未执行恢复。

## 3. 全部数据表及用途

对当前库的 `information_schema.tables` 和每张表执行实际 `COUNT(*)`，结果如下。全部为 `BASE TABLE`，没有视图或其他表。

| 表 | 实际行数 | 用途 | 分类 |
| --- | ---: | --- | --- |
| `user_accounts` | 0 | 系统账号、密码摘要和用户名称 | A：必需 |
| `user_roles` | 0 | 账号角色集合；由 `UserAccount` 的 JPA `@ElementCollection` 映射 | A：必需 |
| `activities` | 0 | 活动、时间、地点、名额、推文与发布状态 | A：必需 |
| `registrations` | 0 | 学生报名及取消记录 | A：必需 |
| `checkins` | 0 | 学生签到及人工补签记录 | A：必需 |
| `announcements` | 0 | 公告与撤回状态 | A：必需 |
| `activity_audits` | 0 | 活动操作审计 | A：必需 |
| `wechat_student_bindings` | 0 | 小程序微信身份与学生账号绑定 | A：必需 |
| `miniapp_access_tokens` | 0 | 小程序令牌哈希、有效期和撤销时间 | A：必需 |

`user_roles` 虽无独立 Entity 类，但被账号实体直接映射，不是冗余表。`activities` 中保留的旧签到令牌字段仍在当前签到服务的旧码兼容路径中读取，不能仅因新码采用 HMAC 就擅自删除。没有发现迁移历史表；当前仓库内有显式 SQL 文件 `backend/migrations/V1__miniapp_student_auth.sql`，但没有据此推断存在自动迁移历史管理器。

分类汇总：A 类九张；B 类缺失表零张；C 类疑似冗余表零张；D 类确认可删除表零张。因此没有待用户确认的具体删表名单，也没有执行清理。

## 4. 字段与实体映射

以下为本次从 MySQL 读取的字段及类型。除标明 `NULL` 的字段外均为 `NOT NULL`；各直接实体表的 `id` 均为 `BIGINT AUTO_INCREMENT` 主键。`DATETIME(6)` 用于实体中的时间字段。

| 表 | 实际字段与类型 |
| --- | --- |
| `user_accounts` | `id BIGINT`，`email VARCHAR(160)`，`name VARCHAR(80)`，`password_hash VARCHAR(255)`，`created_at DATETIME(6)` |
| `user_roles` | `user_id BIGINT`，`role ENUM(ADMIN,ORGANIZER,STUDENT)`；复合主键为 `(user_id, role)` |
| `activities` | `id BIGINT`，`title VARCHAR(160)`，`description VARCHAR(5000)`，`article_summary VARCHAR(300) NULL`，`cover_image_url VARCHAR(1000) NULL`，`location VARCHAR(200)`，`latitude DOUBLE NULL`，`longitude DOUBLE NULL`，`capacity INT`，`registered_count INT`，`registration_opens_at DATETIME(6)`，`registration_closes_at DATETIME(6)`，`starts_at DATETIME(6)`，`ends_at DATETIME(6)`，`checkin_opens_at DATETIME(6)`，`checkin_closes_at DATETIME(6)`，`checkin_token_hash VARCHAR(255) NULL`，`checkin_token_expires_at DATETIME(6) NULL`，`status ENUM(CANCELLED,DRAFT,PENDING,PUBLISHED,REJECTED)`，`review_note VARCHAR(500) NULL`，`published_at DATETIME(6) NULL`，`created_at DATETIME(6)`，`organizer_id BIGINT` |
| `registrations` | `id BIGINT`，`activity_id BIGINT`，`student_id BIGINT`，`registered_at DATETIME(6)`，`cancelled_at DATETIME(6) NULL`，`status ENUM(ACTIVE,CANCELLED)` |
| `checkins` | `id BIGINT`，`activity_id BIGINT`，`student_id BIGINT`，`operator_id BIGINT NULL`，`checked_at DATETIME(6)`，`method ENUM(MANUAL,QR)`，`reason VARCHAR(500) NULL` |
| `announcements` | `id BIGINT`，`title VARCHAR(160)`，`body VARCHAR(3000)`，`active BIT(1)`，`published_at DATETIME(6)` |
| `activity_audits` | `id BIGINT`，`activity_id BIGINT`，`actor_id BIGINT`，`action ENUM(CREATED,DIRECT_PUBLISHED,EDITED,QR_ISSUED,REVIEW_APPROVED,REVIEW_REJECTED)`，`created_at DATETIME(6)` |
| `wechat_student_bindings` | `id BIGINT`，`app_id VARCHAR(32)`，`open_id VARCHAR(128)`，`student_id BIGINT`，`created_at DATETIME(6)` |
| `miniapp_access_tokens` | `id BIGINT`，`binding_id BIGINT`，`token_hash CHAR(64)`，`created_at DATETIME(6)`，`expires_at DATETIME(6)`，`revoked_at DATETIME(6) NULL` |

字段名、基本类型、可空性与当前实体映射匹配。`active BIT(1)` 对应布尔值；数据库的枚举列与当前角色、活动状态、报名状态、签到方法及审计动作枚举一致。最新后端在 MySQL Profile 下完成 JPA `validate`，这是实体与实际表结构匹配的运行时证据；它并不能代替业务数据测试。

## 5. 约束、索引及引用关系

- 主键：八张直接实体表均有单列 `id` 主键；`user_roles` 为 `(user_id, role)` 复合主键。
- 唯一约束：`user_accounts.email`；`registrations(activity_id, student_id)`；`checkins(activity_id, student_id)`；`wechat_student_bindings(app_id, open_id)` 与 `(app_id, student_id)`；`miniapp_access_tokens.token_hash`。其中微信绑定的两个约束和令牌哈希约束与已批准的迁移 SQL 一致。
- 普通索引：关联列上的活动组织者、审计活动/操作者、签到学生/补签操作者、报名学生、绑定学生、令牌绑定及令牌到期时间等索引存在；报名/签到的复合唯一索引也可支持以 `activity_id` 为前缀的查找。
- 外键：`activities.organizer_id → user_accounts.id`；`activity_audits.activity_id → activities.id`、`actor_id → user_accounts.id`；`registrations.activity_id → activities.id`、`student_id → user_accounts.id`；`checkins.activity_id → activities.id`、`student_id → user_accounts.id`、`operator_id → user_accounts.id`；`user_roles.user_id → user_accounts.id`；`wechat_student_bindings.student_id → user_accounts.id`；`miniapp_access_tokens.binding_id → wechat_student_bindings.id`。
- 未发现项目必需而缺失的外键或唯一约束。`activities.status/starts_at` 暂无专用查询索引；空库下没有性能证据表明必须现在新增，待有代表性数据和查询计划时再评估。

本轮没有执行新的迁移 SQL，也没有新增表。`V1__miniapp_student_auth.sql` 是既有文件，本次只核对它与现存两张认证表一致，未重放该脚本，以免在已存在表上报错或造成未经批准的变更。

## 6. 后端连接和接口冒烟验证

- `application.yml` 的 `mysql` Profile 仍为 `spring.jpa.hibernate.ddl-auto=validate`，本轮未改为 `update`、`create` 或 `create-drop`。
- 为避免误认其他进程，使用本轮启动的后端进程和临时端口 `18080` 验证；启动日志显示 JDBC URL 指向 `127.0.0.1:3306/campus_activity_miniapp_dev`、MySQL `8.0.46`、JPA `EntityManagerFactory` 已初始化、Tomcat 在 `18080` 启动。验证结束后临时服务已关闭。
- `GET /api/activities` 返回 HTTP 200 和 `[]`；`GET /api/announcements` 返回 HTTP 200 和 `[]`，符合当前空库实际情况。
- 未认证访问 `GET /api/admin/activities`、`GET /api/miniapp/auth/me` 均返回 HTTP 401。使用格式正确但不存在的 Bearer 凭证请求小程序接口仍返回 401，触发了令牌查找路径；Web 登录用不存在账号测试返回 401，触发了账号查找路径。以上为只读冒烟验证，没有创建账号或令牌。
- 微信绑定表 Repository 的真实 MySQL 读写、管理员已登录操作、组织者审核、学生报名及签到等需要账号和活动数据，不能从空库冒烟结果认定已完成真实 MySQL 端到端验收。

配置注意：若绕过启动脚本直接激活 `mysql` Profile，`application.yml` 的 `DB_URL` 缺省值仍指向另一库 `campus_activity`。本轮没有连接该库；后续操作须显式指定开发库 URL，避免误连。建议在单独的配置整改中评估是否将缺省值改为拒绝启动或指向开发库，未经确认不擅自改变既有运行方式。

## 7. 自动测试和构建

| 检查 | 实际执行 | 结果 | 范围说明 |
| --- | --- | --- | --- |
| 后端 H2 隔离测试 | `backend` 下运行 `mvnw.cmd test -q` | 23 项通过、0 失败、0 错误、0 跳过 | 与本机 MySQL 的数据读写验收不同 |
| 小程序单元测试 | `miniapp` 下运行 `npm test` | 10 项通过、0 失败 | 扫码格式、时间与地图字段等前端规则；有 Node 模块类型提示，不影响通过 |
| 小程序构建 | `miniapp` 下运行 `npm run build:mp-weixin` | 成功 | 仅编译通过，非微信真机验收 |
| 管理端构建 | `frontend` 下运行 `npm run build` | 成功 | 仅构建通过，非登录后业务验收 |
| MySQL 结构校验 | 本轮最新 Spring Boot 以 `mysql` Profile 启动 | 通过 | `ddl-auto=validate`，连接开发库 |

本轮未运行真实微信 `code2Session`，也未验证 HTTPS 合法域名、真机扫码或完整微信登录。没有 AppSecret 时不应以模拟身份替代正式认证。

## 8. 后续条件与待确认事项

1. 目前九张表全为空。若要在开发库验证微信绑定、令牌撤销、报名容量、动态签到和管理员审核，需要另行制定**明确标识为测试数据**的账号及活动初始化方案；本轮未从演示库复制数据。
2. 真实微信登录还需后端安全配置 AppSecret、可访问的接口地址及微信平台合法域名/HTTPS 配置；上述条件不属于本轮结构整理，也尚未验收。
3. 没有待删除表，因此无需给出删表 SQL。将来若出现疑似旧表，必须先列出具体表名、引用关系、行数和保留价值，并取得针对该表的明确确认后才可清理。

总体判定：**数据库结构满足继续开展小程序联调的前置要求；真实身份认证和完整业务联调尚不满足验收条件。**按本轮授权，报告提交后等待确认，不自动进入真实微信登录或视觉重构。
