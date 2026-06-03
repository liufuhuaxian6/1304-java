# 基于 Java 多线程的共享文档管理与版本控制系统

## 1. 项目简介

本项目是一个基于 Java Socket 和多线程的共享文档管理与版本控制系统。系统采用服务器端与客户端分离的结构，支持多个客户端同时连接服务器，已实现文档上传、下载、在线查看、申请编辑权限、保存修改、历史版本管理等功能。

核心业务已落地：登录登出、文档上传/下载/在线预览、编辑锁（同一文档同一时刻仅一人可编辑）、保存、历史版本生成/列表/下载/回滚。除控制台客户端外，服务器还提供基于 Javalin 的 HTTP REST API 与一个简易 Web 前端。当前以内存集合 + 本地文件系统作为存储，未接入数据库，用户数据为内存模拟。

## 2. 项目目标

- 搭建可编译、可运行的 Maven Java 项目结构。
- 使用 Java Socket 预留客户端与服务器通信能力。
- 使用多线程模型为每个客户端连接创建独立处理线程。
- 使用 `synchronized` 和 `ConcurrentHashMap` 预留文档编辑锁机制。
- 使用文件系统目录预留文档与历史版本存储位置。
- 使用面向对象方式划分 model、server、service、storage、client、util 等模块。
- 为后续扩展 Swing、JavaFX、数据库存储或更完整协议设计保留空间。

## 3. 技术路线

- Java 17
- Maven 标准项目结构
- Java Socket 网络通信
- 多线程 `ClientHandler`
- Java Serializable 对象通信占位协议
- `ConcurrentHashMap` 管理内存数据
- `synchronized` 控制编辑锁临界区
- 文件系统存储当前文档与历史版本
- 控制台客户端菜单
- 不使用 Spring Boot
- 不使用数据库

## 4. 系统功能模块

### 用户模块

- 登录 `login`
- 登出 `logout`
- 注册预留 `register`
- 当前阶段使用内存集合模拟用户数据

### 文档管理模块

- 文档列表
- 文档上传（保存到 `data/documents`）
- 文档下载（返回文件字节）
- 文档只读查看（文本预览，超长截断）
- 文档保存（需持有编辑锁）

### 编辑权限模块

- 用户申请编辑权限
- 同一文档同一时刻只允许一个用户编辑
- 获得编辑权限的用户可以保存文档
- 其他用户只能只读查看
- 用户释放编辑权限

### 版本管理模块

- 上传文档时生成初始版本
- 保存文档时生成新版本
- 查看历史版本列表
- 下载历史版本
- 版本回滚（回滚前需持有编辑锁，回滚会追加一条新版本记录）

### 客户端模块

- 控制台菜单
- 登录入口
- 文档列表入口
- 上传、下载、查看入口
- 申请编辑、保存、释放编辑入口
- 历史版本查看、下载、回滚入口

## 5. 并发控制设计

服务器端通过 `ServerSocket` 监听端口。每当有客户端连接时，`ServerMain` 会创建一个新的 `ClientHandler`，并使用独立线程处理该客户端请求。

编辑锁由 `LockService` 统一管理：

- 使用 `ConcurrentHashMap<String, String>` 保存 `documentId -> username` 的锁关系。
- 使用 `synchronized` 修饰 `tryLockDocument` 和 `unlockDocument`，保证申请锁与释放锁操作的原子性。
- 同一篇文档可以被多个用户查看，但只有一个用户能持有编辑锁。
- 后续可扩展锁超时、等待队列、强制释放、用户退出自动释放等机制。

## 6. 版本管理设计

版本信息使用 `DocumentVersion` 表示，包含：

- 版本号 `versionId`
- 文档 ID `documentId`
- 文档名称 `fileName`
- 操作用户 `editor`
- 操作时间 `editTime`
- 操作类型 `operationType`
- 版本文件路径 `versionPath`
- 备注信息 `comment`

`VersionService` 负责版本元数据管理，`VersionStorage` 负责版本文件路径构建与版本文件复制存储。上传与保存时会把当前文件复制为一份版本快照并追加版本记录；回滚时再将指定版本快照还原为当前文件，并生成一条回滚版本记录。

## 7. 上传下载设计

上传下载已实现真实文件读写：

- 客户端读取本地文件字节，封装到 `Request.payload`。
- 服务器端通过 `FileStorage.saveFile` 保存文件。
- 下载当前文档时，服务器通过 `FileStorage.readFile` 读取字节并返回。
- 下载历史版本时，服务器通过 `VersionStorage.readVersionFile` 读取指定版本文件。
- 文件默认目录为 `data/documents`，版本默认目录为 `data/versions`。

## 8. 项目目录结构

```text
.
├── pom.xml
├── README.md
├── data
│   ├── documents
│   └── versions
└── src
    └── main
        └── java
            └── com
                └── sharedoc
                    ├── client
                    │   ├── ClientApp.java
                    │   ├── ClientConnection.java
                    │   └── ClientMain.java
                    ├── model
                    │   ├── Document.java
                    │   ├── DocumentVersion.java
                    │   ├── OperationType.java
                    │   ├── Request.java
                    │   ├── RequestType.java
                    │   ├── Response.java
                    │   └── User.java
                    ├── server
                    │   ├── ClientHandler.java
                    │   ├── HttpApiServer.java
                    │   ├── ServerConfig.java
                    │   └── ServerMain.java
                    ├── service
                    │   ├── DocumentService.java
                    │   ├── LockService.java
                    │   ├── UserService.java
                    │   └── VersionService.java
                    ├── storage
                    │   ├── FileStorage.java
                    │   └── VersionStorage.java
                    └── util
                        ├── DateTimeUtil.java
                        ├── IdGenerator.java
                        └── SerializeUtil.java
```

## 9. 运行方式

### 编译项目

```bash
mvn clean compile
```

### 启动服务器

```bash
mvn exec:java -Dexec.mainClass="com.sharedoc.server.ServerMain"
```

如果没有配置 `exec-maven-plugin`，也可以先编译后使用 `java` 命令运行：

```bash
mvn clean compile
java -cp target/classes com.sharedoc.server.ServerMain
```

### 启动客户端

打开另一个终端：

```bash
java -cp target/classes com.sharedoc.client.ClientMain
```

默认测试用户：

- 用户名：`admin`，密码：`123456`
- 用户名：`user`，密码：`123456`

说明：登录已实现真实密码校验，控制台客户端会提示输入密码；以上为内置测试用户的默认密码。

### 启动 HTTP API 与 Web 前端

`ServerMain` 在启动 Socket 服务（端口 `8889`）的同时，会启动一个 HTTP REST API（端口 `8082`）：

```bash
java -cp target/classes com.sharedoc.server.ServerMain
```

随后用浏览器打开 `frontend/index.html` 即可使用 Web 前端（登录、文档列表、上传、预览、下载、申请/释放编辑、保存）。Web 前端默认请求 `http://localhost:8082`。

主要 HTTP 接口：

- `POST /api/v1/auth/login`、`POST /api/v1/auth/register`、`POST /api/v1/auth/logout`、`GET /api/v1/auth/me`
- `GET /api/v1/documents`、`POST /api/v1/documents`（上传）
- `GET /api/v1/documents/{id}/preview`、`GET /api/v1/documents/{id}/download`
- `POST` / `DELETE /api/v1/documents/{id}/lock`、`PUT /api/v1/documents/{id}/content`
- `GET /api/v1/documents/{id}/versions`、`GET /api/v1/documents/{id}/versions/{versionId}/download`、`POST /api/v1/documents/{id}/versions/{versionId}/rollback`

接口契约详见 [frontend-api-design.md](./frontend-api-design.md)。

## 10. 后续开发计划

1. 完善登录、注册、在线用户管理和退出释放锁逻辑。
2. 实现真实文件上传：客户端读取文件，服务器保存到 `data/documents`。
3. 实现真实文件下载：服务器返回文件字节，客户端保存到本地。
4. 实现只读查看：支持文本文件预览，非文本文件返回元数据。
5. 完善编辑权限：保存前校验当前用户是否持有编辑锁。
6. 实现版本生成：上传生成初始版本，保存生成编辑版本。
7. 实现历史版本列表和历史版本下载。
8. 预留并逐步实现版本回滚。
9. 增加异常处理、日志输出和基础测试用例。
10. 可扩展 Swing/JavaFX 图形界面或数据库存储。

## 11. 四人分工

### 成员 A：服务器与多线程模块

负责：

- `ServerMain`
- `ClientHandler`
- 多客户端连接管理
- 线程池或多线程模型
- 请求分发

### 成员 B：文档管理与上传下载模块

负责：

- `DocumentService`
- `FileStorage`
- 文档列表
- 文档上传
- 文档下载
- 文档查看

### 成员 C：编辑锁与版本管理模块

负责：

- `LockService`
- `VersionService`
- `VersionStorage`
- 编辑权限控制
- 历史版本生成
- 历史版本查看
- 历史版本下载
- 可选版本回滚

### 成员 D：客户端与用户交互模块

负责：

- `ClientMain`
- `ClientApp`
- `ClientConnection`
- 控制台菜单
- 用户登录流程
- 与服务器通信
- README 整理与测试文档

当前成员 D 交付的客户端交接与联调说明见 [handover-D.md](./handover-D.md)。

如需为后续 Web 前端开发准备接口契约，见 [frontend-api-design.md](./frontend-api-design.md)。

## 12. 当前阶段说明

- 核心业务（登录登出、上传/下载/预览、编辑锁、保存、版本生成/列表/下载/回滚）已实现。
- 提供两种接入方式：控制台 Socket 客户端，以及基于 Javalin 的 HTTP REST API + 简易 Web 前端。
- Socket 通信使用 Java Serializable 对象流；HTTP 接口使用 JSON。
- 用户注册已实现（服务层 `UserService.register` 与 HTTP `POST /api/v1/auth/register`），但用户数据仍为内存模拟，未持久化。
- 数据存放于内存集合与本地文件系统，未接入数据库。
- 已包含 JUnit 单元测试（服务层与请求分发）。
- 后续可扩展：数据库存储、图形界面，以及 Web 前端的历史版本管理页面。
