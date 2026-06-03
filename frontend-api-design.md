# 前端联调用 API 设计文档

## 1. 文档说明

- 本文档用于承接成员 D 后续的前端联调工作，先定义前后端接口契约，不包含前端页面实现。
- 当前项目实际通信方式仍是 `Socket + Java Serializable(Request/Response)`，并不是 HTTP 接口。
- 本文档给出的是后续 Web 前端接入时建议采用的 `HTTP + JSON` 设计方案，便于后面单独补前端和接口适配层。

## 2. 设计目标

- 保留现有业务能力：登录、登出、文档列表、上传、下载、查看、申请编辑、保存、释放编辑、版本列表、版本下载、版本回滚。
- 对前端暴露统一的 REST 风格接口，避免直接复用 Java 对象流协议。
- 尽量贴近现有 `DocumentService` / `UserService` / `VersionService` 的行为，降低后续改造成本。
- 明确哪些接口已经有后端业务基础，哪些仍依赖成员 C 的版本模块补齐。

## 3. 当前状态与改造边界

### 3.1 当前项目已有能力

- `LOGIN` / `LOGOUT` 已可用。
- `LIST_DOCUMENTS` / `UPLOAD_DOCUMENT` / `DOWNLOAD_DOCUMENT` / `VIEW_DOCUMENT` / `REQUEST_EDIT` / `SAVE_DOCUMENT` / `RELEASE_EDIT` 已有后端业务实现。
- `LIST_VERSIONS` / `DOWNLOAD_VERSION` / `ROLLBACK_VERSION` 目前仍是占位实现，接口可以先定，真实能力后补。

### 3.2 后续前端接入建议

- 新增一层 HTTP 适配接口，不直接让浏览器连接当前 Socket 服务。
- HTTP 层负责：
  - 接收 JSON / `multipart/form-data`
  - 调用现有 service
  - 将 `Response` 转换为统一 JSON 结构
  - 处理登录态、鉴权、文件流下载

## 4. 全局约定

### 4.1 Base URL

```text
/api/v1
```

### 4.2 鉴权方式

- 采用 `Bearer Token` 方案，适合后续 Web 前端。
- 登录成功后返回 `token`。
- 除登录接口外，其余接口默认要求请求头：

```http
Authorization: Bearer <token>
```

说明：

- 当前后端实际是“连接级登录状态”，不是 token 模型。
- 后续实现 HTTP 层时，可先用内存 `token -> username` 映射完成最小可用版本。

### 4.3 时间字段格式

- 统一使用 ISO 8601 字符串，例如：

```text
2026-06-03T14:30:00
```

### 4.4 统一响应结构

成功：

```json
{
  "success": true,
  "code": "OK",
  "message": "文档列表获取成功",
  "data": {}
}
```

失败：

```json
{
  "success": false,
  "code": "DOCUMENT_LOCKED",
  "message": "文档正在被其他用户编辑",
  "data": null
}
```

### 4.5 通用错误码

| 错误码 | 含义 |
| --- | --- |
| `OK` | 成功 |
| `BAD_REQUEST` | 请求参数错误 |
| `AUTH_REQUIRED` | 未登录或 token 无效 |
| `INVALID_CREDENTIALS` | 用户名或密码错误 |
| `FORBIDDEN` | 无权限访问 |
| `DOCUMENT_NOT_FOUND` | 文档不存在 |
| `DOCUMENT_LOCKED` | 文档已被其他用户占用 |
| `NO_EDIT_PERMISSION` | 当前用户未持有编辑权限 |
| `UNSUPPORTED_FILE_TYPE` | 不支持的文件类型 |
| `VERSION_NOT_FOUND` | 版本不存在 |
| `VERSION_NOT_READY` | 版本功能尚未完成 |
| `INTERNAL_ERROR` | 服务端异常 |

### 4.6 HTTP 状态码建议

| 场景 | 状态码 |
| --- | --- |
| 查询成功 | `200 OK` |
| 创建成功 | `201 Created` |
| 登出成功且无返回体 | `204 No Content` |
| 参数错误 | `400 Bad Request` |
| 未登录 | `401 Unauthorized` |
| 无权限 | `403 Forbidden` |
| 资源不存在 | `404 Not Found` |
| 编辑锁冲突 | `409 Conflict` |
| 文件类型不支持 | `415 Unsupported Media Type` |
| 服务异常 | `500 Internal Server Error` |

## 5. 核心数据结构

### 5.1 UserInfo

```json
{
  "userId": "U-ADMIN",
  "username": "admin",
  "role": "ADMIN"
}
```

### 5.2 DocumentItem

```json
{
  "documentId": "D-1001",
  "fileName": "README.md",
  "owner": "admin",
  "uploadTime": "2026-06-03T10:00:00",
  "lastModifiedTime": "2026-06-03T10:30:00",
  "editingUser": "user",
  "editingStartTime": "2026-06-03T10:35:00",
  "isEditing": true
}
```

字段说明：

- `isEditing` 为前端友好字段，可由后端根据 `editingUser != null` 直接计算返回。
- `currentPath` 属于服务端内部存储字段，不建议暴露给前端。

### 5.3 DocumentPreview

```json
{
  "document": {
    "documentId": "D-1001",
    "fileName": "README.md",
    "owner": "admin",
    "uploadTime": "2026-06-03T10:00:00",
    "lastModifiedTime": "2026-06-03T10:30:00",
    "editingUser": null,
    "editingStartTime": null,
    "isEditing": false
  },
  "contentText": "# 文档内容",
  "isTextFile": true,
  "truncated": false
}
```

说明：

- 当前后端查看接口本质来自 `VIEW_DOCUMENT`。
- 首版可继续沿用当前逻辑，只保证部分文本扩展名可在线预览。
- 若文件不可预览，则 `contentText = null`，并通过 `message` 提示前端改走下载。

### 5.4 VersionItem

```json
{
  "versionId": "V-2001",
  "documentId": "D-1001",
  "fileName": "README.md",
  "editor": "admin",
  "editTime": "2026-06-03T11:00:00",
  "operationType": "EDIT",
  "comment": "修正文档标题"
}
```

说明：

- `versionPath` 为服务端内部字段，不建议返回前端。

## 6. 接口清单总览

| 接口 | 方法 | 鉴权 | 对应现有能力 | 当前状态 |
| --- | --- | --- | --- | --- |
| `/auth/login` | `POST` | 否 | `LOGIN` | 已有业务基础 |
| `/auth/logout` | `POST` | 是 | `LOGOUT` | 已有业务基础 |
| `/auth/me` | `GET` | 是 | 新增辅助接口 | 需 HTTP 层补充 |
| `/documents` | `GET` | 是 | `LIST_DOCUMENTS` | 已有业务基础 |
| `/documents` | `POST` | 是 | `UPLOAD_DOCUMENT` | 已有业务基础 |
| `/documents/{documentId}/preview` | `GET` | 是 | `VIEW_DOCUMENT` | 已有业务基础 |
| `/documents/{documentId}/download` | `GET` | 是 | `DOWNLOAD_DOCUMENT` | 已有业务基础 |
| `/documents/{documentId}/lock` | `POST` | 是 | `REQUEST_EDIT` | 已有业务基础 |
| `/documents/{documentId}/lock` | `DELETE` | 是 | `RELEASE_EDIT` | 已有业务基础 |
| `/documents/{documentId}/content` | `PUT` | 是 | `SAVE_DOCUMENT` | 已有业务基础 |
| `/documents/{documentId}/versions` | `GET` | 是 | `LIST_VERSIONS` | 仅占位 |
| `/documents/{documentId}/versions/{versionId}/download` | `GET` | 是 | `DOWNLOAD_VERSION` | 仅占位 |
| `/documents/{documentId}/versions/{versionId}/rollback` | `POST` | 是 | `ROLLBACK_VERSION` | 仅占位 |

## 7. 详细接口设计

### 7.1 登录

`POST /api/v1/auth/login`

请求体：

```json
{
  "username": "admin",
  "password": "123456"
}
```

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "登录成功",
  "data": {
    "token": "token-admin-xxx",
    "user": {
      "userId": "U-ADMIN",
      "username": "admin",
      "role": "ADMIN"
    }
  }
}
```

失败场景：

- 用户不存在：`404 + INVALID_CREDENTIALS`
- 密码错误：`401 + INVALID_CREDENTIALS`
- 参数为空：`400 + BAD_REQUEST`

### 7.2 登出

`POST /api/v1/auth/logout`

请求体：

```json
{}
```

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "登出成功",
  "data": null
}
```

说明：

- 登出时需要同步释放当前用户持有的编辑锁，对齐现有 `UserService.logout()` 行为。

### 7.3 获取当前登录用户

`GET /api/v1/auth/me`

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "获取当前用户成功",
  "data": {
    "userId": "U-ADMIN",
    "username": "admin",
    "role": "ADMIN"
  }
}
```

说明：

- 该接口主要服务于前端刷新页面后的登录态恢复。
- 当前 Socket 协议没有该请求类型，可在 HTTP 层直接根据 token 返回。

### 7.4 获取文档列表

`GET /api/v1/documents`

查询参数：

- 首版无需分页、搜索、筛选。
- 如后续需要，可再扩展 `keyword`、`owner`、`editingStatus`、`page`、`pageSize`。

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "文档列表获取成功",
  "data": [
    {
      "documentId": "D-1001",
      "fileName": "README.md",
      "owner": "admin",
      "uploadTime": "2026-06-03T10:00:00",
      "lastModifiedTime": "2026-06-03T10:30:00",
      "editingUser": null,
      "editingStartTime": null,
      "isEditing": false
    }
  ]
}
```

### 7.5 上传文档

`POST /api/v1/documents`

请求类型：

```text
multipart/form-data
```

表单字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | File | 是 | 上传文件 |

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "文档上传成功",
  "data": {
    "document": {
      "documentId": "D-1001",
      "fileName": "README.md",
      "owner": "admin",
      "uploadTime": "2026-06-03T10:00:00",
      "lastModifiedTime": "2026-06-03T10:00:00",
      "editingUser": null,
      "editingStartTime": null,
      "isEditing": false
    }
  }
}
```

失败场景：

- 未登录：`401 + AUTH_REQUIRED`
- 文件为空：`400 + BAD_REQUEST`
- 文件类型不支持：`415 + UNSUPPORTED_FILE_TYPE`

说明：

- 对应当前 `UPLOAD_DOCUMENT` 的 payload：
  - `fileName`
  - `fileContent`

### 7.6 在线预览文档

`GET /api/v1/documents/{documentId}/preview`

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "文档查看成功",
  "data": {
    "document": {
      "documentId": "D-1001",
      "fileName": "README.md",
      "owner": "admin",
      "uploadTime": "2026-06-03T10:00:00",
      "lastModifiedTime": "2026-06-03T10:30:00",
      "editingUser": null,
      "editingStartTime": null,
      "isEditing": false
    },
    "contentText": "# Hello",
    "isTextFile": true,
    "truncated": false
  }
}
```

失败场景：

- 文档不存在：`404 + DOCUMENT_NOT_FOUND`
- 文件不可在线预览：`200`，但 `contentText = null`，`isTextFile = false`

说明：

- 首版可继续维持当前服务端的截断规则：预览长度超过 1000 字符时返回截断内容，并标记 `truncated = true`。

### 7.7 下载当前文档

`GET /api/v1/documents/{documentId}/download`

成功响应：

- 返回二进制文件流，不走 JSON 包装。

响应头建议：

```http
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="README.md"
X-Document-Id: D-1001
```

失败响应：

- 可返回 JSON 错误体，例如 `404 + DOCUMENT_NOT_FOUND`

### 7.8 申请编辑锁

`POST /api/v1/documents/{documentId}/lock`

请求体：

```json
{}
```

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "编辑权限申请成功",
  "data": {
    "documentId": "D-1001",
    "editingUser": "admin"
  }
}
```

失败场景：

- 文档不存在：`404 + DOCUMENT_NOT_FOUND`
- 未登录：`401 + AUTH_REQUIRED`
- 已被他人占用：`409 + DOCUMENT_LOCKED`

### 7.9 释放编辑锁

`DELETE /api/v1/documents/{documentId}/lock`

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "编辑权限已释放",
  "data": null
}
```

失败场景：

- 文档不存在：`404 + DOCUMENT_NOT_FOUND`
- 当前用户未持有锁：`403 + NO_EDIT_PERMISSION`

### 7.10 保存文档内容

`PUT /api/v1/documents/{documentId}/content`

请求体：

```json
{
  "contentText": "# 新内容",
  "comment": "更新说明，可为空"
}
```

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "文档保存成功",
  "data": {
    "documentId": "D-1001",
    "fileName": "README.md",
    "owner": "admin",
    "uploadTime": "2026-06-03T10:00:00",
    "lastModifiedTime": "2026-06-03T11:00:00",
    "editingUser": "admin",
    "editingStartTime": "2026-06-03T10:50:00",
    "isEditing": true
  }
}
```

失败场景：

- 文档不存在：`404 + DOCUMENT_NOT_FOUND`
- 未持有编辑锁：`403 + NO_EDIT_PERMISSION`
- 内容为空：`400 + BAD_REQUEST`

说明：

- 当前 `SAVE_DOCUMENT` 实际接收的是 `byte[]`。
- HTTP 层可先将 `contentText` 按 `UTF-8` 转为字节数组再调用现有保存逻辑。
- `comment` 字段本轮只先保留，不强制接入现有业务；后续成员 C 完成版本管理后可用于版本备注。

### 7.11 获取版本列表

`GET /api/v1/documents/{documentId}/versions`

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "版本列表获取成功",
  "data": [
    {
      "versionId": "V-2001",
      "documentId": "D-1001",
      "fileName": "README.md",
      "editor": "admin",
      "editTime": "2026-06-03T11:00:00",
      "operationType": "EDIT",
      "comment": "修正文档标题"
    }
  ]
}
```

当前状态：

- 接口先定义。
- 现有 `VersionService.listVersions()` 仍为占位实现，后续需补真实版本元数据。

### 7.12 下载历史版本

`GET /api/v1/documents/{documentId}/versions/{versionId}/download`

成功响应：

- 返回二进制文件流。

当前状态：

- 仅定义接口。
- 现有 `VersionService.downloadVersion()` 仍未实现真实文件读取。

### 7.13 回滚到指定版本

`POST /api/v1/documents/{documentId}/versions/{versionId}/rollback`

请求体：

```json
{
  "comment": "回滚到稳定版本"
}
```

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "版本回滚成功",
  "data": {
    "documentId": "D-1001",
    "versionId": "V-2001"
  }
}
```

当前状态：

- 仅定义接口。
- 现有 `VersionService.rollbackToVersion()` 仍是占位逻辑。

## 8. 与现有 Java 对象协议的映射

| HTTP API | 当前 RequestType | 当前 payload / 返回值 |
| --- | --- | --- |
| `POST /auth/login` | `LOGIN` | `payload = password` |
| `POST /auth/logout` | `LOGOUT` | 无 |
| `GET /documents` | `LIST_DOCUMENTS` | `Response.data = List<Document>` |
| `POST /documents` | `UPLOAD_DOCUMENT` | `payload = Map{fileName, fileContent}` |
| `GET /documents/{id}/preview` | `VIEW_DOCUMENT` | `Response.data = Map{document, preview, isTextFile}` |
| `GET /documents/{id}/download` | `DOWNLOAD_DOCUMENT` | `Response.data = Map{document, fileContent}` |
| `POST /documents/{id}/lock` | `REQUEST_EDIT` | 无 |
| `DELETE /documents/{id}/lock` | `RELEASE_EDIT` | 无 |
| `PUT /documents/{id}/content` | `SAVE_DOCUMENT` | `payload = byte[]` |
| `GET /documents/{id}/versions` | `LIST_VERSIONS` | `Response.data = List<DocumentVersion>` |
| `GET /documents/{id}/versions/{vid}/download` | `DOWNLOAD_VERSION` | 当前未完成 |
| `POST /documents/{id}/versions/{vid}/rollback` | `ROLLBACK_VERSION` | 当前未完成 |

## 9. 前端页面与接口对应关系

| 前端页面/模块 | 主要接口 |
| --- | --- |
| 登录页 | `POST /auth/login` |
| 顶部用户状态 | `GET /auth/me`、`POST /auth/logout` |
| 文档列表页 | `GET /documents`、`POST /documents` |
| 文档详情/预览弹窗 | `GET /documents/{id}/preview`、`GET /documents/{id}/download` |
| 在线编辑页 | `POST /documents/{id}/lock`、`PUT /documents/{id}/content`、`DELETE /documents/{id}/lock` |
| 历史版本面板 | `GET /documents/{id}/versions`、`GET /documents/{id}/versions/{vid}/download`、`POST /documents/{id}/versions/{vid}/rollback` |

## 10. 本轮不做的内容

- 不编写前端页面。
- 不实现 HTTP Controller / Servlet / 网关层。
- 不改造成 Vue / React 工程。
- 不补版本模块的真实读写逻辑。

## 11. 后续实施建议

1. 先在后端补一层 HTTP API 适配层，把本文档中的接口跑通。
2. 优先打通登录、文档列表、上传、预览、下载、申请编辑、保存、释放编辑这 8 组接口。
3. 版本列表、版本下载、版本回滚等成员 C 相关接口可先返回“未完成”错误码，再逐步补真逻辑。
4. 前端正式开做时，优先落三个页面：登录页、文档列表页、文档编辑页。

