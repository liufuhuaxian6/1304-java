# 前端联调 API 说明

## 1. 说明

本文档描述当前 HTML 前端实际使用的 HTTP API。

- 前端入口：`frontend/index.html`
- 前端脚本：`frontend/app.js`
- API Base URL：`http://localhost:8082/api/v1`
- 鉴权方式：`Authorization: Bearer <token>`
- 实时同步：`GET /documents/{documentId}/events` 使用 SSE，前端也会把 token 作为查询参数传入

统一响应结构：

```json
{
  "success": true,
  "code": "OK",
  "message": "文档列表获取成功",
  "data": {}
}
```

失败时：

```json
{
  "success": false,
  "code": "BAD_REQUEST",
  "message": "错误信息",
  "data": null
}
```

## 2. 认证接口

### `POST /auth/login`

请求体：

```json
{
  "username": "admin",
  "password": "123456"
}
```

成功响应中的 `data`：

```json
{
  "token": "token-xxx",
  "user": {
    "userId": "U-ADMIN",
    "username": "admin"
  }
}
```

### `POST /auth/register`

请求体：

```json
{
  "username": "new-user",
  "password": "123456",
  "role": "USER"
}
```

### `POST /auth/logout`

需要 `Bearer Token`。登出会释放当前用户持有或排队中的编辑锁。

### `GET /auth/me`

返回当前登录用户。

## 3. 文档接口

### `GET /documents`

返回文档列表，`data` 为数组。

单项示例：

```json
{
  "documentId": "D-1",
  "fileName": "README.md",
  "owner": "admin",
  "uploadTime": "2026-06-08T23:00:00",
  "lastModifiedTime": "2026-06-08T23:00:00",
  "editingUser": null,
  "editingStartTime": null,
  "activeLockCount": 0,
  "revision": 1
}
```

### `POST /documents`

请求类型：

```text
multipart/form-data
```

字段：

- `file`: 上传文件

上传后会创建初始完整版本。

### `GET /documents/{documentId}/preview`

返回预览信息，`data` 包含：

```json
{
  "document": {},
  "preview": "# hello",
  "contentText": "# hello",
  "isTextFile": true,
  "truncated": false
}
```

其中 `contentText` 是前端编辑器实际使用字段。

### `GET /documents/{documentId}/download`

返回当前文档二进制文件流。

## 4. 编辑与实时同步接口

### `POST /documents/{documentId}/lock`

申请文档区间锁。前端通常会把光标或选区扩展到完整行后再申请锁。

请求体：

```json
{
  "revision": 1,
  "start": 0,
  "end": 12
}
```

成功响应中的 `data`：

```json
{
  "lockId": "L-1",
  "documentId": "D-1",
  "start": 0,
  "end": 12,
  "revision": 1,
  "owner": "admin",
  "queued": false,
  "queuePosition": 0,
  "activeLocks": []
}
```

说明：

- 不重叠区间可以被不同用户同时持有。
- 重叠区间会进入等待队列，响应中 `queued=true`，并返回 `queuePosition`。
- 服务端会通过 SSE 广播 `lock-acquired` 或 `lock-queued`。

### `DELETE /documents/{documentId}/lock`

释放当前用户在该文档上的活动锁和排队锁。释放后如果等待队列中有可编辑区间，服务端会晋升对应锁并广播 `lock-acquired`。

### `PATCH /documents/{documentId}/content`

局部保存当前锁区间内容。旧的 `PUT /content` 已禁用，会返回 405。

请求体：

```json
{
  "lockId": "L-1",
  "clientRevision": 1,
  "replacementText": "新的区间文本",
  "comment": "区间编辑保存"
}
```

成功响应中的 `data.contentUpdate` 包含：

```json
{
  "revisionBefore": 1,
  "revisionAfter": 2,
  "start": 0,
  "end": 12,
  "replacementText": "新的区间文本",
  "delta": 2,
  "activeLocks": []
}
```

保存成功后：

- 服务端只替换锁定区间，不要求前端提交全文。
- 当前锁会释放，其他锁的坐标会按本次编辑长度变化平移。
- 服务端创建或合并一个 PATCH 历史版本。
- 服务端广播 `content-updated` 和 `lock-released`。

### `GET /documents/{documentId}/events`

SSE 实时事件流。前端使用：

```text
GET /documents/{documentId}/events?token=<token>
```

主要事件：

- `lock-acquired`: 有区间锁被获取或等待锁被晋升。
- `lock-queued`: 有用户进入等待队列。
- `lock-released`: 有区间锁释放。
- `content-updated`: 文档局部内容已保存，包含 `start`、`end`、`replacementText`、`revisionBefore`、`revisionAfter`、`editor`、`activeLocks`。
- `document-rolled-back`: 文档已回滚，其他在线用户应重新加载当前文档内容和版本号。

前端在本地存在未保存编辑时，会把远端 `content-updated` 的服务端坐标映射到当前 textarea 坐标后再应用，避免并发编辑错位。

## 5. 版本接口

### `GET /documents/{documentId}/versions`

返回版本列表。

单项示例：

```json
{
  "versionId": "V-2",
  "documentId": "D-1",
  "fileName": "README.md",
  "editor": "admin",
  "operationType": "EDIT",
  "editTime": "2026-06-08T23:10:00",
  "comment": "区间编辑保存",
  "storageType": "PATCH",
  "patchCount": 2
}
```

说明：

- `storageType=FULL` 表示该版本保存完整文件内容，通常用于上传版本和回滚版本。
- `storageType=PATCH` 表示该版本只保存修改片段，物理文件通常是 `data/versions/{documentId}/V-x-{fileName}.patch.json`。
- 同一用户在 60 秒内连续保存会合并为一个 PATCH 版本，`patchCount` 表示该版本包含的修改片段数量。
- 合并版本的备注会按分号拆分并去重，历史弹窗也会对旧数据做展示去重。

### `GET /documents/{documentId}/versions/{versionId}/diff`

返回指定版本相对上一个版本的差异。历史版本弹窗点击表格行时调用该接口。

成功响应中的 `data`：

```json
{
  "version": {},
  "previousVersion": {},
  "changes": [
    {
      "start": 0,
      "end": 5,
      "type": "REPLACE",
      "removedText": "hello",
      "addedText": "hi",
      "removedLine": 1,
      "addedLine": 1
    }
  ]
}
```

`type` 可能为 `ADD`、`DELETE` 或 `REPLACE`。如果目标版本是合并后的 PATCH 版本，`changes` 会列出其中每个修改片段。

### `GET /documents/{documentId}/versions/{versionId}/download`

返回历史版本文件流。即使版本底层是 PATCH 存储，下载接口也会先重建完整文件内容再返回。

### `POST /documents/{documentId}/versions/{versionId}/rollback`

将文档回滚到指定版本。

限制与同步行为：

- 只要该文档存在任何活动或排队区间锁，回滚会返回 `409 ACTIVE_LOCKS_PRESENT`。
- 回滚成功会创建一个新的 FULL 版本，不会删除历史版本。
- 服务端会广播 `document-rolled-back`，其他在线用户收到后刷新当前文档内容。

## 6. 前端对应关系

- 登录页：`POST /auth/login`
- 登录态恢复：`GET /auth/me`
- 登出：`POST /auth/logout`
- 文档列表：`GET /documents`
- 上传：`POST /documents`
- 预览/编辑内容加载：`GET /documents/{id}/preview`
- 下载：`GET /documents/{id}/download`
- 申请编辑区间：`POST /documents/{id}/lock`
- 保存区间：`PATCH /documents/{id}/content`
- 释放编辑区间：`DELETE /documents/{id}/lock`
- 文档实时事件：`GET /documents/{id}/events`
- 历史版本：`GET /documents/{id}/versions`
- 版本差异：`GET /documents/{id}/versions/{versionId}/diff`
- 版本下载：`GET /documents/{id}/versions/{versionId}/download`
- 版本回滚：`POST /documents/{id}/versions/{versionId}/rollback`
