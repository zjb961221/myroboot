# MYROBOOT Support 架构说明与演进计划

## 1. 当前定位

本系统当前适合定位为“单实例、私有化部署的客户技术支持平台”。核心流程是：

1. 客户登录。
2. 搜索问题库，自助解决。
3. 未解决时提交工单和附件。
4. 管理员记录处理过程并填写回执。
5. 客户查看处理时间线和回执附件。

现阶段不建议为了“微服务化”拆成多个服务。业务规模尚不需要 Spring Cloud、消息队列或独立检索集群。优先保证数据一致性、权限、可观测性和可维护性。

## 2. 当前技术栈

- Frontend: Vue 3 + Vite + Nginx
- Backend: Spring Boot 3.3 / Java 17 / JdbcTemplate
- Database: MySQL 8
- File storage: Docker named volume
- Search: MySQL FULLTEXT + ngram + LIKE fallback
- Deployment: Docker Compose

## 3. 当前主要架构问题

### P0：必须优先处理

#### 3.1 文件访问权限

目前上传文件使用随机 UUID 文件名，但 `/api/uploads/{filename}` 本身没有细粒度业务授权。UUID 降低了猜测概率，但不能视作真正权限控制。

目标方案：文件访问统一经过 FileService，由系统判断当前用户是否有权访问所属 FAQ、工单或处理记录。为了兼容 `<img>`、`<video>`、PDF iframe 等浏览器原生资源加载，推荐后续将 Bearer Token 登录迁移为 HttpOnly SameSite Cookie 会话，然后文件请求即可自然携带会话。

不建议把长期 Bearer Token 放到文件 URL 查询参数中。

#### 3.2 数据库迁移机制

目前同时使用 `schema.sql` 和 `DatabaseMigration` 的启动期 try/catch 迁移。这种方式在字段越来越多以后难以审计，也无法明确知道某个数据库执行过哪些版本。

目标方案：引入 Flyway，并采用版本化 SQL：

- V1 baseline
- V2 ticket attachments
- V3 FAQ attachments
- V4 persistent sessions
- ...

现有数据库必须先做 baseline，不能直接删除或重建 volume。

#### 3.3 多 SQL 写操作事务

FAQ 保存、工单创建、处理回执等都涉及多条 SQL，需要放入 Service 层并使用 `@Transactional`。否则中途异常可能形成半完成数据。

### P1：近期处理

#### 3.4 Controller 职责过重

`SupportController` 目前同时承担：

- FAQ 查询和搜索
- FAQ 管理
- 工单创建
- 工单管理
- 附件关联

目标结构：

```text
controller/
  AuthController
  FaqController
  AdminFaqController
  TicketController
  AdminTicketController
  FileController
  AdminLogController

service/
  AuthService
  FaqService
  TicketService
  FileService
  EmailVerificationService

repository/
  FaqRepository
  TicketRepository
  UserRepository
```

第一阶段仍然可以继续使用 JdbcTemplate，不需要为了“分层”强行切换 JPA。

#### 3.5 Map<String,Object> 作为业务模型

大量 Controller 使用 Map 接收和返回业务数据，字段名只能运行时发现错误。

目标：请求使用 Java record DTO + Jakarta Validation，例如：

```java
public record CreateTicketRequest(
    @NotBlank String category,
    @NotBlank String description,
    List<FileRef> attachments
) {}
```

查询层可暂时保留 Map，随后逐步改成明确 View DTO。

#### 3.6 前端缺少统一 API 层

多个 Vue 页面重复实现：

- Authorization Header
- 401 跳转
- API 错误解析
- fetch

目标增加 `src/api/http.js`，业务页面只调用 `faqApi.search()`、`ticketApi.create()` 等函数。

#### 3.7 路由

当前根据 `window.location.pathname` 手工挂载不同 Vue Root。页面继续增加后会越来越难维护。

目标迁移 Vue Router，但不要求立刻做。先保持现有 URL 不变，再逐页迁移。

## 4. 已实施的基础优化

当前分支已经开始实施第一阶段：

- 持久化登录会话，后端重启不立即导致用户掉线。
- 统一客户友好错误提示。
- 每个 HTTP 请求加入 `X-Request-Id`。
- 日志记录 requestId、请求方法、URI、状态和耗时。
- 未知服务端异常返回 requestId，客户可直接把编号发给技术人员。
- 管理后台可查看后端日志。
- 增加 `/api/health`，检查 MySQL 和上传目录。
- Docker Compose backend healthcheck。
- Frontend 等待 backend 健康后启动。
- Nginx `client_max_body_size` 提升到 240MB，匹配 200MB 视频附件需求。
- 大文件代理超时调整，并关闭 request buffering。
- HikariCP 连接池参数显式化。
- Java 容器限制 JVM 使用容器内存比例，并在 OOM 时退出让 Docker 重启。
- 增加 GitHub Actions：后端编译 + 前端构建。
- `.env` 已被 `.gitignore` 排除。

## 5. 推荐的目标部署结构

对于当前规模，推荐继续保持单机 Compose：

```text
Browser
   |
 Nginx :8088
   |-- static Vue
   `-- /api -> Spring Boot
                 |-- MySQL 8
                 |-- upload volume
                 `-- log volume
```

暂时不要增加 Redis、Kafka、Elasticsearch。只有出现以下条件再引入：

- Redis：多 backend 实例共享 session / 限流。
- Elasticsearch/OpenSearch：FAQ 数量达到数万以上且 MySQL ngram 搜索质量明显不足。
- MQ：邮件、通知、文件处理出现明显异步任务压力。
- Object Storage：附件容量达到几十 GB 或需要多实例共享文件。

## 6. 文件体系目标

所有上传文件最终统一成一个逻辑 File 对象：

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
```

业务关系单独保存：

```text
faq_file
  faq_id
  file_id

ticket_file
  ticket_id
  file_id
  usage        # customer / progress / resolution
  history_id
```

这样可以解决目前不同附件表字段重复、未来难以统一权限、删除和磁盘清理的问题。

## 7. 搜索策略

当前 MySQL FULLTEXT ngram + LIKE fallback 对 MVP 足够。

建议排序权重：

1. 标题完整命中
2. 标题分词命中
3. 分类命中
4. keywords 命中
5. answer 正文命中

后续 RAG 也不应该直接替换现有搜索，而应该作为第二层：

```text
关键词检索 -> Top N FAQ -> RAG 总结/问答
```

避免模型脱离已维护的问题库回答。

## 8. 日志与可观测性

当前 requestId 是第一步。下一阶段建议日志关键事件结构化：

```text
LOGIN_SUCCESS userId=...
LOGIN_FAILED username=...
MAIL_CODE_SENT emailDomain=qq.com
TICKET_CREATED ticketId=...
FAQ_UPDATED faqId=...
FILE_UPLOADED fileId=... size=...
```

禁止记录：

- 密码
- SMTP 授权码
- 完整 Bearer Token
- 验证码

生产环境日志保留 14 天或总量 500MB，并由后台日志页只读取限定行数。

## 9. 安全目标

近期需要逐步加入：

- 登录失败限流。
- 邮箱验证码按邮箱 + IP 双维度限流。
- 默认 admin 密码启动告警。
- 文件实际类型校验，不能只依赖扩展名。
- Cookie 会话 + HttpOnly + SameSite。
- 上传文件访问鉴权。
- 富文本 HTML 白名单清洗，防止持久型 XSS。
- CORS 从 `*` 收敛到明确来源。

## 10. 分阶段实施顺序

### Phase 1：稳定性和可观测性

- [x] Request ID
- [x] 服务端异常日志
- [x] 管理后台日志
- [x] 健康检查
- [x] Nginx 大附件配置
- [x] Docker 健康依赖
- [x] CI 构建
- [ ] 事务边界
- [ ] 基础自动化测试

### Phase 2：代码结构

- [ ] FaqService / TicketService
- [ ] Repository 层
- [ ] DTO + Validation
- [ ] 前端统一 API Client
- [ ] Vue Router
- [ ] 删除重复的 AdminKnowledge 旧入口

### Phase 3：安全和数据治理

- [ ] Flyway
- [ ] 统一 FileService
- [ ] 附件鉴权
- [ ] 登录/验证码限流
- [ ] HTML sanitizer
- [ ] 文件 SHA-256 和重复文件治理
- [ ] 上传孤儿文件清理

### Phase 4：规模化能力

只有实际数据量和并发需要时再做：

- Redis
- 对象存储 MinIO/S3
- OpenSearch/Elasticsearch
- 异步任务队列
- 多实例部署

## 11. 原则

这个系统下一阶段最重要的不是“技术更多”，而是：

- 每个错误可以定位。
- 每次写入要么全部成功，要么全部失败。
- 每个文件知道属于谁、谁能看。
- 每个接口有明确输入输出。
- 每次提交代码自动验证能编译。
- 数据库升级可追踪、可回滚。

在这些基础做好之前，不建议引入微服务或复杂中间件。
