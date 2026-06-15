# 共享文档管理与版本控制系统

## 项目简介

这是一个基于 Java 17、Javalin 和原生 HTML 前端的多人共享编辑系统。

系统当前只保留 Web 访问方式：

- 后端提供 HTTP REST API + SSE 实时事件，并直接托管前端静态页面
- 前端使用 `frontend/index.html` + `frontend/app.js`（Vue 3 CDN）
- 文档内容、历史版本以及用户/文档/版本元数据持久化到本地文件系统，重启后自动恢复
- 会话、在线状态和编辑锁保存在内存中（按设计不跨重启保留）

## 功能总览

### 账号与会话

- 登录 / 登出 / 注册（前端登录页提供注册入口）
- 密码以 PBKDF2（HmacSHA256，65536 轮加盐）哈希存储，服务端不保存明文，接口响应中永不包含密码字段
- 账号持久化到 `users.json`，注册的用户重启后依然可登录
- 注册账号固定为 `USER` 角色，无法通过注册获得管理员权限
- 登录失败统一提示"用户名或密码错误"，不暴露用户名是否存在
- 会话 token 使用滑动过期（默认 30 分钟），过期后自动失效
- 登出会自动释放当前用户持有或排队中的全部编辑锁

### 文档管理

- 文档列表、上传、下载、在线预览、重命名、删除
- 删除会一并清除该文档的全部历史版本文件；仅所有者或 `ADMIN` 可删除/重命名，且删除要求无活动锁
- 上传文件名经过净化（剥离目录部分、过滤非法字符与控制字符），防止路径穿越
- 上传大小限制（默认 5 MB）与文件类型白名单
- 下载使用 RFC 5987 编码的 `Content-Disposition`，中文文件名可正常下载

### 协同编辑

- 细粒度区间锁：不同用户可并行编辑同一文档的不重叠区间
- 重叠区间进入 FIFO 等待队列，前序锁释放后自动晋升并通过 SSE 通知
- 活动锁带 TTL（默认 10 分钟）：浏览器异常关闭、断网等情况下的“僵尸锁”会自动过期释放，不会永久阻塞他人编辑
- 关闭页面/标签时通过 `beforeunload` + `fetch keepalive` 主动释放锁，不必等 TTL
- 在线用户列表（presence）：SSE 连接/断开时广播在看该文档的用户集合
- 局部内容保存（PATCH，只提交锁区间文本），保存后其他锁坐标自动平移
- SSE 推送：锁申请 / 释放 / 排队晋升、内容局部更新、版本回滚、文档重命名/删除、在线状态变化

### 版本控制

- 初始上传与回滚保存完整快照（FULL），普通编辑只保存修改片段（PATCH）
- 同一用户 60 秒内的连续编辑合并为一个 PATCH 版本以节省空间
- 每累计 20 个连续 PATCH 版本自动落一个 FULL 快照，版本重建从最近的快照开始重放，成本有上界
- 历史版本列表、下载、相邻版本差异查看（基于行级 LCS diff，多处改动分别展示）
- 版本回滚：仅文档所有者或 `ADMIN` 可回滚；存在活动锁时禁止回滚；回滚生成新版本并同步刷新其他在线用户

### 持久化与部署

- 文档元数据、版本索引、用户账号分别持久化到 `data/metadata/` 下的 `documents.json` / `versions.json` / `users.json`
- 写入采用「临时文件 + 原子重命名」，进程崩溃不会留下半截文件
- 启动时自动加载，并把 ID 序列恢复到已有最大值之上，避免新建文档/用户与历史 ID 冲突
- 后端直接托管 `frontend/` 静态页面：浏览器打开 `http://localhost:8082/` 即可使用，无需单独的静态服务器，同源访问也不触发跨域

### 安全设计

- 认证拦截在 Javalin `before` 处理器中通过抛出 `UnauthorizedResponse` 中断请求管线，未认证请求不会执行任何业务处理器（有专门的回归测试覆盖"401 不泄露响应体"）；CORS 预检 OPTIONS 请求例外放行
- 注册用户名/密码做格式校验（用户名 2-32 位、字母数字下划线连字符或中文；密码 6-64 位）
- 文档内部存储路径（`currentPath`）通过 `@JsonIgnore` 对客户端隐藏，仅用于持久化
- 服务层返回机器可读错误码（`ErrorCodes`），HTTP 层按错误码映射状态码，不做消息文本匹配
- 服务端异常只记录日志，对客户端返回通用错误信息，不泄露内部路径和堆栈
- CORS 默认仅允许 `http://localhost:8003` / `http://127.0.0.1:8003`，可通过环境变量配置
- 日志不记录 token 与密码

## 技术栈

- Java 17 / Maven
- Javalin 5（Jetty 11）
- Jackson（统一的 JSON 序列化，含 JSR-310 日期支持）
- SLF4J + slf4j-simple
- Vue 3 CDN + Tailwind CSS CDN + highlight.js
- JUnit 5

## 项目结构

```text
.
├── Dockerfile              # 多阶段构建，运行可执行 fat jar（target/app.jar）
├── frontend
│   ├── index.html          # 登录/注册、文档列表、编辑器、历史版本弹窗、改密码
│   └── app.js              # Vue 3 应用：锁管理、自动保存、SSE 同步、坐标映射
├── data
│   ├── documents           # 当前文档文件（运行时生成）
│   ├── versions            # 历史版本文件（FULL 快照 + *.patch.json）
│   └── metadata            # 元数据：documents.json / versions.json / users.json
├── src
│   ├── main/java/com/sharedoc
│   │   ├── model           # Document / DocumentVersion / RangeLock / Response / ErrorCodes ...
│   │   ├── server          # HttpApiServer / DocumentEventBroker / ServerConfig / ServerMain
│   │   ├── service         # DocumentService / LockService / VersionService / UserService
│   │   ├── storage         # FileStorage / VersionStorage / JsonStore / StoredUser / StoredDocument
│   │   └── util            # PasswordHasher / FileNames / IdGenerator / DateTimeUtil
│   └── test/java/com/sharedoc
│       ├── server          # HTTP 冒烟流程 + 安全回归测试（越权、提权、权限、CORS 预检、改密码、删除）
│       ├── service         # 文档（增删改）/ 锁（TTL）/ 版本（快照）/ 用户（改密码）/ 持久化测试
│       └── testutil        # 测试状态重置工具
└── pom.xml
```

## 架构说明

- **服务装配**：所有服务均为实例状态（无静态可变共享状态），由 `ServerMain` 显式装配；`DocumentService` 与 HTTP 层共享同一个 `VersionService` / `LockService` 实例。测试通过创建全新实例获得隔离，不再依赖反射清理。
- **并发模型**：同一文档的所有操作通过每文档监视器（per-document monitor）串行化；`LockService` 内部用 `synchronized` 保护锁表并在每次访问时清理过期锁。
- **保存一致性**：保存流程为"写文件 → 记版本"，版本记录失败时会把文件内容恢复为保存前状态，保证磁盘内容与版本历史不会发散。
- **持久化**：每个服务拥有自己的元数据文件，通过 `JsonStore`（原子写、同步保护）在变更后落盘；锁与在线状态属于易失状态，按设计不持久化，重启后文档的编辑状态从实时锁重新计算。

## 配置

所有配置都有默认值，可通过环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SHAREDOC_HTTP_PORT` | `8082` | HTTP 监听端口 |
| `SHAREDOC_DOCUMENT_DIR` | `data/documents` | 当前文档存储目录 |
| `SHAREDOC_VERSION_DIR` | `data/versions` | 历史版本存储目录 |
| `SHAREDOC_METADATA_DIR` | `data/metadata` | 元数据（用户/文档/版本索引）目录 |
| `SHAREDOC_FRONTEND_DIR` | `frontend` | 后端托管的前端静态目录（目录不存在则不托管） |
| `SHAREDOC_SESSION_TTL_MINUTES` | `30` | 会话滑动过期时间（分钟） |
| `SHAREDOC_LOCK_TTL_MINUTES` | `10` | 编辑锁过期时间（分钟） |
| `SHAREDOC_MAX_UPLOAD_BYTES` | `5242880` | 上传大小上限（字节） |
| `SHAREDOC_CORS_ORIGINS` | `http://localhost:8003,http://127.0.0.1:8003` | 允许的跨域来源，逗号分隔，`*` 表示全部放开（仅限开发） |

前端 API 地址默认 `http://localhost:8082/api/v1`，可在加载 `app.js` 之前设置 `window.SHAREDOC_API_BASE` 覆盖。

## 启动

开发模式（直接用 Maven 运行，mainClass 已写入 pom，无需额外参数）：

```bash
mvn clean compile
mvn exec:java
```

打包为可执行 fat jar 并运行：

```bash
mvn clean package          # 生成 target/app.jar
java -jar target/app.jar
```

Docker 运行（数据持久化到挂载卷）：

```bash
docker build -t sharedoc .
docker run -p 8082:8082 -v sharedoc-data:/data sharedoc
```

容器内 `/data` 通过 VOLUME 持久化，并暴露 `GET /api/v1/health` 健康检查。

后端默认监听 `http://localhost:8082`，并在该端口直接托管前端页面。

### 方式一：后端托管前端（推荐）

启动后直接在浏览器打开：

```text
http://localhost:8082/
```

前后端同源，无需额外的静态服务器，也不涉及跨域。

### 方式二：独立静态服务器（可选）

如果想单独跑前端（例如改前端时用热重载工具），仍可用静态服务器：

```bash
cd frontend
python -m http.server 8003
```

然后打开 `http://localhost:8003/`。此模式为跨域访问，默认 CORS 配置已允许 8003 端口；其他端口需通过 `SHAREDOC_CORS_ORIGINS` 放行。

## 默认账号

- `admin / 123456`（ADMIN 角色，可回滚任意文档）
- `user / 123456`（USER 角色）

首次启动时若 `data/metadata/users.json` 不存在，会自动创建这两个账号（密码以 PBKDF2 哈希存储）。此后账号数据从该文件加载，新注册的用户会一并持久化，重启不丢失。新账号可通过登录页的"立即注册"入口创建。

## 主要接口

- `GET /api/v1/health` — 健康检查（无需认证）
- `POST /api/v1/auth/login` — 登录，返回 token 与用户信息（含角色）
- `POST /api/v1/auth/register` — 注册（角色固定为 USER）
- `POST /api/v1/auth/logout` — 登出并释放编辑锁
- `POST /api/v1/auth/password` — 修改密码
- `GET /api/v1/auth/me` — 当前用户信息
- `GET /api/v1/documents` — 文档列表
- `POST /api/v1/documents` — 上传（multipart，大小/类型/文件名校验）
- `DELETE /api/v1/documents/{id}` — 删除文档（owner/ADMIN）
- `PATCH /api/v1/documents/{id}` — 重命名文档（owner/ADMIN）
- `GET /api/v1/documents/{id}/preview` — 在线预览
- `GET /api/v1/documents/{id}/content` — 编辑器全文加载
- `GET /api/v1/documents/{id}/download` — 下载当前文档
- `POST /api/v1/documents/{id}/lock` — 申请区间锁
- `DELETE /api/v1/documents/{id}/lock` — 释放区间锁
- `PATCH /api/v1/documents/{id}/content` — 局部保存
- `GET /api/v1/documents/{id}/events` — SSE 实时事件
- `GET /api/v1/documents/{id}/versions` — 历史版本列表
- `GET /api/v1/documents/{id}/versions/{versionId}/diff` — 版本差异
- `GET /api/v1/documents/{id}/versions/{versionId}/download` — 版本下载
- `POST /api/v1/documents/{id}/versions/{versionId}/rollback` — 版本回滚（owner/ADMIN）

完整请求/响应格式与错误码表见 [frontend-api-design.md](./frontend-api-design.md)。

## 测试

```bash
mvn test
```

当前共 51 个测试，覆盖：

- 文档服务：上传 / 预览 / 下载、删除（含权限与活动锁拦截）、重命名（含权限/类型校验）、区间锁并行与排队晋升、坐标平移、补丁合并、并发保存串行化、回滚权限、文件名路径穿越净化、上传大小限制、**CRLF 换行规范化为 LF（前后端偏移一致）**
- 锁服务：过期锁自动释放、过期后排队锁晋升
- 版本服务：长补丁链自动快照与重建正确性
- 用户服务：登录 / 注册（含格式校验）/ 登出释放锁、角色固定 USER、密码不外泄、修改密码
- 持久化：文档/版本/用户跨“重启”（重建服务实例）恢复、ID 不冲突
- HTTP 层：完整冒烟流程、未认证 401 不泄露数据、currentPath 不外泄、CORS 预检放行、注册无法提权、删除/重命名/改密码端点、非所有者回滚 403、健康检查

> 测试数据隔离在 `target/test-data/`（通过 surefire 环境变量配置），运行 `mvn test` 不会触碰真实的 `data/` 目录。

## 当前限制

- 数据持久化为本地 JSON 文件 + 文件系统，单节点部署，未考虑多实例横向扩展或数据库
- 每次变更全量重写对应元数据文件，文档量很大时写放大明显（当前规模无影响）
- 编辑锁与在线状态按设计保存在内存中，重启后清空（文档内容、版本、账号会从 `data/` 恢复）
- 版本差异为行级 LCS diff（非字符级 Myers diff），重命名只改显示名、物理文件名不变
- 在线状态只展示“谁在看该文档”，未做实时光标坐标广播
- 中文输入法（IME）合成输入在区间锁边界处的行为未做专门处理
