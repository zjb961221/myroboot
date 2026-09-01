# MYROBOOT Support 架构说明与演进计划

## 1. 系统定位

MYROBOOT Support 当前定位为**单实例、私有化部署的客户技术支持平台**。核心流程：

1. 客户登录并搜索问题库。
2. 标准方案无法解决时提交工单及附件。
3. 管理员记录处理过程、附件和最终回执。
4. 客户查看处理时间线、原因和回执资料。

当前规模不建议为了“架构先进”引入微服务、Kafka、Redis 或 Elasticsearch。现阶段更重要的是数据一致性、权限、可观测性、自动化验证和可维护性。

## 2. 技术栈

- Frontend: Vue 3 + Vite + Nginx
- Backend: Spring Boot 3.3 / Java 17 / JdbcTemplate
- Database: MySQL 8
- File storage: Docker named volume
- Search: MySQL FULLTEXT ngram + LIKE fallback
- Deployment: Docker Compose
- CI: GitHub Actions

## 3. 当前已完成的架构优化

### 3.1 稳定性与可观测性

- [x] 每个 HTTP 请求分配 `X-Request-Id`。
- [x] 日志记录 requestId、请求方法、URI、状态码和耗时。
- [x] 未知服务端错误把 requestId 返回给客户，方便从后台日志反查。
- [x] 管理员后台可直接查看后端日志。
- [x] `/api/health` 检查数据库和上传目录。
- [x] 管理员系统诊断接口可查询数据库、磁盘、SMTP 和 JVM 状态。
- [x] 日志滚动：单文件 20MB、历史保留上限 14、总量上限 500MB。
- [x] Spring Boot graceful shutdown。
- [x] JVM 使用容器内存比例并在 OOM 时退出，让 Docker 自动恢复。
- [x] HikariCP 连接池参数显式化。

### 3.2 Docker / Nginx

- [x] Backend healthcheck，Frontend 等待 Backend 真正健康后启动。
- [x] MySQL healthcheck。
- [x] 200MB 视频上传链路：Spring 220MB / Request 240MB / Nginx 240MB。
- [x] 大文件代理超时和 `proxy_request_buffering off`。
- [x] 静态 hash 资源一年 immutable cache，HTML 不强缓存。
- [x] gzip。
- [x] CSP、`X-Content-Type-Options`、`X-Frame-Options`、Referrer Policy。
- [x] MySQL 与 Backend 默认只绑定 `127.0.0.1`，默认仅 Frontend 对外开放。

### 3.3 认证与安全

- [x] 会话持久化到 MySQL，后端重启不直接导致全部掉线。
- [x] 登录会话有效期配置化。
- [x] HttpOnly + SameSite=Lax Cookie，会继续兼容现有 Bearer Token。
- [x] 登录失败限流。
- [x] 验证码按 IP 限流。
- [x] 验证码按邮箱持久化小时限流。
- [x] 默认 `admin/admin123` 配置启动告警。
- [x] 注册、人工新增、Excel 导入统一至少 8 位密码。
- [x] SMTP 配置错误提供中文可读提示，不再静默失败。
- [x] QQ 邮箱误配 IMAP 时明确提示应使用 `smtp.qq.com`。

### 3.4 文件安全与治理

- [x] 上传文件建立 24 小时 staging ownership。
- [x] `/api/uploads/{filename}` 不再是匿名公开资源。
- [x] 管理员可查看所有业务附件。
- [x] FAQ 附件仅对已登录用户开放，并要求 FAQ 已启用。
- [x] 工单、处理过程和回执附件只允许工单所属客户访问。
- [x] 客户不能通过拿到其他工单 URL 越权读取文件。
- [x] 每日清理超过宽限时间且数据库无引用的孤儿文件。
- [x] 数据库引用读取异常时采用 fail-safe：宁可不删除，也不误删文件。
- [x] Excel/TXT/LOG 预览限制读取范围，避免预览接口一次读取整个大文件。

### 3.5 数据一致性与性能

- [x] FAQ 创建、更新、删除使用事务。
- [x] 工单创建和附件关联使用事务。
- [x] 处理记录和回执使用事务。
- [x] 工单创建自动写第一条时间线。
- [x] 工单状态变化自动写时间线。
- [x] FAQ 列表附件查询从 N+1 改为批量查询。
- [x] 工单列表附件查询从 N+1 改为批量查询。
- [x] 处理时间线附件从 N+1 改为批量查询。
- [x] 兼容数据库迁移不再静默吞异常，会记录迁移结果和失败原因。

### 3.6 代码结构

原 `SupportController` 已开始拆层：

```text
controller/
  SupportController       # HTTP 路由和权限边界
  AuthController
  UploadController
  TicketHistoryController
  AdminLogController
  AdminSystemController
  ...

service/
  AuthService
  FaqService              # FAQ 查询、搜索、附件关联和事务
  TicketService           # 工单创建、列表、状态和事务
  EmailVerificationService
  AuthRateLimitService
  UploadCleanupService
```

当前仍使用 JdbcTemplate。**暂时不因为“分层”而迁移 JPA**；JdbcTemplate 对当前数据规模和 SQL 可控性更合适。

### 3.7 质量门

- [x] GitHub Actions 后端执行 `mvn verify`。
- [x] GitHub Actions 前端执行 production build。
- [x] 已增加认证限流单元测试。
- [x] PR 保持 Draft，继续通过真实部署验证后再合并。

## 4. 当前仍需处理的技术债

### P0 / 下一阶段优先

#### 4.1 数据库迁移迁到 Flyway

当前仍是 `schema.sql + DatabaseMigration` 的兼容模式。已经做到迁移可观测，但长期必须改为版本化迁移。

不能直接在现有数据库上强推 Flyway，需要：

1. 对当前生产/测试库做 schema baseline。
2. 建立 `V1__baseline.sql` 或 Flyway baseline version。
3. 后续只新增 `V2__...`, `V3__...`。
4. 先在现有 Docker volume 副本验证升级，再停用 `DatabaseMigration`。

**不允许为了迁移方便执行 `docker compose down -v`。**

#### 4.2 DTO + Jakarta Validation

Controller 仍有较多 `Map<String,Object>` 请求体。下一步应逐步变成明确 DTO，例如：

```java
public record CreateTicketRequest(
    @NotBlank
    @Size(max = 100)
    String category,

    @NotBlank
    @Size(max = 20000)
    String description,

    List<FileRef> attachments
) {}
```

收益：字段错误编译期/启动期更容易发现，Swagger/OpenAPI 也更容易接入。

#### 4.3 统一文件对象模型

目前 FAQ、工单、时间线分别有附件表。权限已经补上，但字段仍重复。

长期目标：

```text
file_object
  id
  storage_key
  original_name
  content_type
  size
  sha256
  uploader_id
  created_time

faq_file
  faq_id
  file_id

ticket_file
  ticket_id
  file_id
  usage        # customer / progress / resolution
  history_id
```

这样才能统一文件去重、权限、审计、删除和未来 MinIO/S3 迁移。

#### 4.4 文件内容类型校验

当前已有扩展名白名单和大小限制，但仍应增加文件 magic/signature 检查。不能完全信任客户端 Content-Type 和扩展名。

优先检查：图片、PDF、Office ZIP container、视频容器。

### P1 / 维护性优化

#### 4.5 Repository 层

`FaqService` / `TicketService` 已经拆出，但 SQL 仍直接写在 Service。下一阶段可按复杂度拆：

```text
FaqRepository
TicketRepository
UserRepository
FileRepository
```

不要机械地“一张表一个 Repository”，以业务聚合为单位即可。

#### 4.6 前端统一 API Client

当前多个 Vue 页面仍重复：

- Bearer Header
- 401 跳转
- `fetch`
- API 错误解析

目标：

```text
src/api/http.js
src/api/auth.js
src/api/faq.js
src/api/ticket.js
```

业务组件不再自己处理 Authorization 和 JSON 错误。

#### 4.7 Vue Router

当前仍通过 `window.location.pathname` 选择 Vue Root。功能继续增长后，应迁 Vue Router。

迁移时保持现有 URL：

```text
/
/register
/admin
/admin/users/manage
/admin/ticket-detail
/ticket-detail
```

不要为了路由重构同时改 URL，降低升级风险。

#### 4.8 富文本白名单清洗

目前 Nginx CSP 已经阻止脚本和危险内联行为，是第一层保护。

下一阶段增加统一 `RichContent` 组件，在渲染 FAQ HTML 前通过 DOMPurify/服务端 sanitizer 做白名单清洗，形成双层保护。

## 5. 搜索架构

当前 MySQL FULLTEXT ngram + LIKE fallback 对现阶段足够。

排序建议：

1. 标题完整命中
2. 标题分词命中
3. 分类命中
4. keywords 命中
5. answer 正文命中

只有 FAQ 数量达到数万级、复杂同义词需求明显或 MySQL 排序效果不够时，再评估 OpenSearch/Elasticsearch。

未来 RAG 不应该替代检索，而应建立在已维护的问题库之上：

```text
用户问题
   -> 关键词 / 全文检索 Top N
   -> 权限过滤
   -> RAG 总结
   -> 引用实际 FAQ / 附件
```

模型不能脱离企业问题库自由编造处理方案。

## 6. 推荐部署拓扑

当前继续保持单机 Compose：

```text
Browser
   |
Nginx :8088              <-- 唯一默认对外入口
   |-- Vue static
   `-- /api
        |
     Spring Boot :8080    <-- 默认仅 127.0.0.1
        |-- MySQL :3306   <-- 默认仅 127.0.0.1
        |-- upload_data
        `-- log_data
```

以下条件出现后再升级基础设施：

- Redis：Backend 多实例，需要共享 session / distributed rate limit。
- MinIO/S3：附件几十 GB、多实例或需要对象生命周期管理。
- OpenSearch：FAQ 数万级且 MySQL 搜索无法满足排序/同义词。
- MQ：邮件、视频转码、通知等异步任务明显增加。

## 7. 运维原则

生产环境必须做到：

- `.env` 不进 Git。
- 修改默认管理员密码。
- HTTPS 环境设置 `SESSION_COOKIE_SECURE=true`。
- 数据库/Backend 不直接暴露公网。
- 定期备份 MySQL 与 `upload_data`。
- 日志里不得记录密码、SMTP 授权码、验证码、完整 Bearer Token。
- 升级前备份，不删除 Docker volume。
- 遇到客户报错优先使用页面提供的 requestId 查后台日志。

## 8. 后续实施顺序

### Phase 1 — 已完成主体

- [x] Request ID
- [x] 错误日志
- [x] 后台日志
- [x] 健康检查
- [x] Docker 健康依赖
- [x] 大附件链路
- [x] 事务边界
- [x] N+1 优化
- [x] CI
- [x] 基础自动化测试

### Phase 2 — 已开始

- [x] FaqService
- [x] TicketService
- [ ] Repository 层
- [ ] DTO + Validation
- [ ] 前端 API Client
- [ ] Vue Router

### Phase 3 — 安全与数据治理

- [x] HttpOnly Cookie 兼容层
- [x] 附件对象权限
- [x] 登录限流
- [x] 验证码双维度限流
- [x] CSP
- [x] 孤儿文件清理
- [ ] 文件 magic/signature 校验
- [ ] RichContent sanitizer
- [ ] Flyway
- [ ] 统一 file_object

### Phase 4 — 按规模决定

- [ ] Redis
- [ ] MinIO/S3
- [ ] OpenSearch/Elasticsearch
- [ ] 异步任务队列
- [ ] 多实例部署

这些不是当前必须项，不应为了技术栈数量提前引入。

## 9. 架构原则

这个项目后续坚持以下原则：

1. **能单体解决的问题，不先拆微服务。**
2. **任何多表写入，要么全部成功，要么全部失败。**
3. **任何附件都必须明确谁上传、属于什么业务、谁有权访问。**
4. **错误必须能通过 requestId 定位。**
5. **任何提交都必须经过自动构建和测试。**
6. **数据库升级必须有版本和可追踪历史。**
7. **先测到真实性能瓶颈，再引入 Redis、ES、MQ。**
8. **兼容已有数据优先于“重构得漂亮”。**
