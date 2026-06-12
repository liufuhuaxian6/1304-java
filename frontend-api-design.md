# 前端联调 API 说明

## 1. 说明

本文档描述当前 HTML 前端实际使用的 HTTP API。

- 前端入口：`frontend/index.html`
- 前端脚本：`frontend/app.js`
- API Base URL：`http://localhost:8082/api/v1`
- 鉴权方式：`Authorization: Bearer <token>`

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

需要 `Bearer Token`。

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
  "editingStartTime": null
}
```

### `POST /documents`

请求类型：

```text
multipart/form-data
```

字段：

- `file`: 上传文件

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

其中 `contentText` 是前端实际使用字段。

### `GET /documents/{documentId}/download`

返回二进制文件流。

## 4. 编辑接口

### `POST /documents/{documentId}/lock`

申请编辑锁。

成功时：

```json
{
  "success": true,
  "code": "OK",
  "message": "编辑权限申请成功",
  "data": {
    "documentId": "D-1",
    "editingUser": "admin"
  }
}
```

### `DELETE /documents/{documentId}/lock`

释放编辑锁。

### `PUT /documents/{documentId}/content`

请求体：

```json
{
  "contentText": "# updated"
}
```

## 5. 版本接口

### `GET /documents/{documentId}/versions`

返回版本列表。

单项示例：

```json
{
  "versionId": "V-1",
  "documentId": "D-1",
  "fileName": "README.md",
  "editor": "admin",
  "operationType": "EDIT",
  "editTime": "2026-06-08T23:10:00",
  "comment": "编辑保存版本"
}
```

### `GET /documents/{documentId}/versions/{versionId}/download`

返回历史版本文件流。

### `POST /documents/{documentId}/versions/{versionId}/rollback`

将文档回滚到指定版本，要求当前用户已持有该文档编辑锁。

## 6. 前端对应关系

- 登录页：`POST /auth/login`
- 登录态恢复：`GET /auth/me`
- 登出：`POST /auth/logout`
- 文档列表：`GET /documents`
- 上传：`POST /documents`
- 预览：`GET /documents/{id}/preview`
- 下载：`GET /documents/{id}/download`
- 申请编辑：`POST /documents/{id}/lock`
- 保存：`PUT /documents/{id}/content`
- 释放编辑：`DELETE /documents/{id}/lock`
- 历史版本：`GET /documents/{id}/versions`
- 版本下载：`GET /documents/{id}/versions/{versionId}/download`
- 版本回滚：`POST /documents/{id}/versions/{versionId}/rollback`
