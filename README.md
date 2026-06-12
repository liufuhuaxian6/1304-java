# 共享文档管理与版本控制系统

## 项目简介

这是一个基于 Java 17、Javalin 和原生 HTML 前端的共享编辑系统。

系统当前只保留 Web 访问方式：

- 后端提供 HTTP REST API
- 前端使用 `frontend/index.html` + `frontend/app.js`
- 文档内容存储在本地文件系统
- 用户、在线状态和编辑锁保存在内存中

当前已实现的核心能力：

- 登录 / 登出 / 注册
- 文档列表、上传、下载、在线预览
- 细粒度区间锁控制，不同用户可并行编辑同一文档的不重叠区间
- 局部内容保存与 SSE 实时同步
- 增量版本存储：初始版本保存完整快照，编辑版本只保存修改片段
- 单位时间内同一用户的连续编辑会合并为一个 PATCH 版本以节省空间
- 历史版本列表、下载、差异查看、回滚；回滚后其他在线用户会同步刷新文档

## 技术栈

- Java 17
- Maven
- Javalin 5
- Gson / Jackson
- Vue 3 CDN
- Tailwind CSS CDN

## 项目结构

```text
.
├── frontend
│   ├── index.html
│   └── app.js
├── data
│   ├── documents
│   └── versions
├── src
│   ├── main
│   │   └── java/com/sharedoc
│   │       ├── model
│   │       ├── server
│   │       ├── service
│   │       ├── storage
│   │       └── util
│   └── test
│       └── java/com/sharedoc
└── pom.xml
```

## 后端启动

编译项目：

```powershell
mvn clean compile
```

启动后端：

```powershell
mvn exec:java "-Dexec.mainClass=com.sharedoc.server.ServerMain"
```

后端默认监听：

- HTTP API: `http://localhost:8082`

## 前端启动

前端是静态页面，需要一个简单静态服务器。

进入前端目录：

```bash
cd frontend
```

如果本机有 Python：

```bash
python -m http.server 8003
```

然后打开：

```text
http://localhost:8003/
```

前端默认请求：

```text
http://localhost:8082/api/v1
```

## 默认测试账号

- `admin / 123456`
- `user / 123456`

这些账号定义在 `src/main/java/com/sharedoc/service/UserService.java` 中，服务重启后内存状态会重置。

## 主要接口

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`
- `GET /api/v1/documents`
- `POST /api/v1/documents`
- `GET /api/v1/documents/{id}/preview`
- `GET /api/v1/documents/{id}/download`
- `POST /api/v1/documents/{id}/lock`
- `DELETE /api/v1/documents/{id}/lock`
- `PATCH /api/v1/documents/{id}/content`
- `GET /api/v1/documents/{id}/events`
- `GET /api/v1/documents/{id}/versions`
- `GET /api/v1/documents/{id}/versions/{versionId}/diff`
- `GET /api/v1/documents/{id}/versions/{versionId}/download`
- `POST /api/v1/documents/{id}/versions/{versionId}/rollback`

接口说明见 [frontend-api-design.md](./frontend-api-design.md)。

## 编辑与版本说明

- 前端根据光标或选区申请行级扩展后的区间锁，锁响应包含 `lockId`、`start`、`end`、`revision`、`queued`、`queuePosition` 和当前活动锁列表。
- 保存使用 `PATCH /content`，请求只提交当前锁区间的新文本，服务端按锁的最新区间写回并释放锁。
- 文档事件通过 `GET /events` 的 SSE 推送，包括锁申请/释放、内容局部更新、队列晋升和版本回滚。
- `data/versions/` 中上传和回滚版本保存为完整文件；普通编辑版本保存为 `*.patch.json`，其中记录 `start/end`、原文本、新文本和修订号。
- 历史版本弹窗点击版本行会请求 `/versions/{versionId}/diff`，显示该版本相对上一版本的新增、删除或替换内容。

## 测试

运行全部测试：

```bash
mvn test
```

当前测试覆盖：

- 文档服务
- 用户服务
- HTTP API 冒烟流程

## 当前限制

- 用户数据未持久化，重启后会恢复默认账号
- 编辑锁保存在内存中，服务重启后会清空
- 前端是静态页面，需要单独启动静态文件服务
- 暂未接入数据库和更完整的会话超时机制
