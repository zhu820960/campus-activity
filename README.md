# 校园活动报名及动态签到平台

系统使用 Spring Boot 后端、Vue Web 管理工作台和 uni-app（Vue 3）微信小程序学生端工程。Web 学生页面已移除，管理员与组织者工作台保留。后端默认使用本地 H2 数据库，也可通过 `mysql` profile 连接 MySQL。小程序已有活动大厅、列表、详情、地图、我的、登录绑定和扫码确认页面及受保护接口；真实微信登录和真机报名签到尚未验收，不能视为完整业务演示通过。

## 功能

- 学生：小程序读取已发布活动和公告，展示详情与地图；微信绑定、报名、取消、签到和个人记录接口已实现，复用原业务服务。真实微信服务和真机链路待验收。
- 组织者：创建活动及推文、提交审核、查看报名名单、展示限时二维码、人工补签、查看报名与到场统计。
- 管理员：独立活动工作台、创建并直接发布自己的活动、审核组织者活动、按活动查看报名与签到统计、展示现场动态二维码、公告与组织者权限管理。创建、编辑、发布、审核和生成二维码均保留操作记录。
- AI 文案：组织者使用 DeepSeek API 生成活动文案草稿，人工检查、修改后保存。未配置 API Key 时，其他功能仍可使用。

## 本地运行

需要 Java 17、Node.js 24+。后端附带 Maven Wrapper，无须单独安装 Maven。

默认配置使用本地 H2 数据文件。MySQL profile 使用 `ddl-auto=validate`，须先在**明确指定的目标库**完成对应迁移；本轮小程序开发只批准并迁移了 `campus_activity_miniapp_dev`，不要把新表 SQL 运行到演示库。程序首次启动时，可通过环境变量创建管理员账号；此密码仅用于初始化，不写入仓库。

PowerShell 示例：

```powershell
$env:APP_BOOTSTRAP_ADMIN_EMAIL = 'admin@example.test'
$env:APP_BOOTSTRAP_ADMIN_PASSWORD = '请设置至少八位的密码'
cd backend
.\mvnw.cmd spring-boot:run
```

另开一个终端：

```powershell
cd frontend
npm install
npm run dev
```

浏览器访问前端终端输出的地址，通常为 `http://localhost:5173`。Web 登录页仅供管理员与组织者使用，学生账号不能进入 Web 工作台。管理员登录后直接进入独立工作台，并可在“用户权限”中授予组织者权限。管理员账号可按上面的环境变量首次初始化，密码不写入仓库。

MySQL 模式必须显式设置 `DB_URL`，缺失或指向其他数据库时后端拒绝启动。当前开发阶段仅允许本机 `127.0.0.1:3306/campus_activity_miniapp_dev`；后续若部署生产环境，须另行审核并调整此安全限制。不要连接演示库：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/campus_activity_miniapp_dev?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
$env:DB_USER = '你的数据库用户'
$env:DB_PASSWORD = '你的数据库密码'
```

本机小程序联调可改用 `pwsh -File .\backend\start-local-mysql.ps1`：脚本在终端隐藏读取密码，核对已存在的 `campus_activity_miniapp_dev` 开发库后，以 MySQL profile 启动后端；不会自动建库。密码只对当前进程有效，不写入项目文件。退出后再次启动需重新输入。

需要真实微信 `code2Session` 时，可加 `-PromptWechatSecret`，在终端隐藏输入 AppSecret；脚本结束后会清理该进程环境变量。不要把 AppSecret 写到命令行参数、聊天、前端文件或日志。没有可用的 HTTPS 合法请求域名时，这仍不能视为小程序真机联网验收。

本机已准备加密的开发测试账号文件时，可在 Windows PowerShell 中进入 `backend/` 并执行 `.\start-local-live-wechat.ps1`。它会从本机加密文件载入测试管理员初始化凭据，依次隐藏读取 MySQL 密码和微信 AppSecret，并在 `18080` 启动后端。请保持该终端打开；真实微信登录仍需微信小程序的临时 code 和可用的 HTTPS 合法请求域名。本轮运行结果与部署前提见《微信小程序真实登录与全链路联调报告.md》。

AI 文案功能需要设置 `DEEPSEEK_API_KEY`。模型可通过 `DEEPSEEK_MODEL` 调整，默认 `deepseek-flash`。密钥只在后端使用，请勿提交到代码仓库。

地图使用高德 Web 端（JS API）Key。启动后端前设置 `AMAP_JS_KEY` 和 `AMAP_SECURITY_JS_CODE` 环境变量；第二项仅留在后端进程中，前端通过同源的 `/_AMapService` 代理访问地图服务。不要把安全密钥写进 Vue 代码或提交到仓库。部署时需同时反向代理 `/api` 和 `/_AMapService` 到后端。示例：

```powershell
$env:AMAP_JS_KEY = '你的 Web 端 JS API Key'
$env:AMAP_SECURITY_JS_CODE = '你的安全密钥'
```

新活动须填写文字地点并在地图上点击选点；管理员活动详情页展示地图。已有旧活动如果没有坐标，仍只显示文字地点。当前不提供地点关键词搜索，地图点选和缩放可用；系统不采集学生位置。

## 使用顺序

1. 管理员初始化账号，并为需要管理活动的教师或社团负责人授予组织者权限。学生小程序认证与账号关联已在独立开发库实施，见《微信小程序认证与学生业务接口实施报告.md》。
2. 组织者创建活动草稿并提交；管理员审核通过后活动对学生可见。管理员自己创建的活动可直接发布，无需自审。
3. 学生经微信登录和原账号绑定后可通过小程序报名、取消并查看个人记录；名额与时间规则仍由后端原服务处理。真实微信和 HTTPS 联调尚未完成。
4. 签到时间内，组织者或管理员可以生成 90 秒动态二维码。小程序扫码、结构化解析及受保护提交接口已实现，服务端仍校验令牌、报名资格和重复签到；真机尚未验收。组织者仅操作自己的活动，管理员可查看全平台活动；补签需填写原因并保留操作人与时间。

二维码不再包含 Web 签到链接。普通手机相机或微信扫一扫不会自动打开小程序；小程序内扫码需在真实微信登录、HTTPS 域名和真机条件齐备后联调。后端可通过 `CHECKIN_TOKEN_SECRET` 设置至少 32 字节的随机签名密钥；勿提交密钥。未配置时开发环境使用进程内临时密钥，服务重启后此前签发的二维码立即失效，多实例部署必须为各实例配置同一密钥。签到不采集学生位置。

封面文件上传尚未实现，当前可填 HTTPS 图片地址或使用默认图。详见 `项目进度与待办.md`。

## 项目结构

- `backend/`：Spring Boot API 与数据库实体。
- `frontend/`：仅管理员与组织者的 Vue Web 工作台。
- `miniapp/`：新增的 uni-app 微信小程序学生端工程；进度见《小程序转型实施清单.md》。

小程序工程先在 `miniapp/` 运行 `npm install`、`npm test`、`npm run build:mp-weixin`，再将 `miniapp/dist/build/mp-weixin/` 导入微信开发者工具。根据 `miniapp/.env.example` 在本地配置 `VITE_API_BASE_URL`，正式环境必须是已登记为小程序合法请求域名的 HTTPS API 地址；不要把 `localhost` 当作手机可访问地址，也不要把 AppSecret 放入前端。后端通过 `WECHAT_APP_ID`、`WECHAT_APP_SECRET` 环境变量配置微信服务；无真实 AppSecret 时不会签发正式微信登录凭证。详见《微信小程序认证与学生业务接口实施报告.md》。

默认 H2 开发环境仍使用 JPA 自动更新结构；MySQL profile 已切换为结构校验。小程序认证表采用显式版本化 SQL。正式部署还需完整迁移策略，并为管理员设置独立强密码。
