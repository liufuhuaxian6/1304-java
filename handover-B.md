# 成员 B 交接说明

———————————————5.25——————————————————

## 1. 本次已完成内容

本次交付聚焦成员 B 负责的文档管理与上传下载部分，实现了完整的文件上传、下载、查看和列表功能。

已完成：

- `FileStorage`
  - 实现文件保存：`saveFile()`，支持自动创建目录
  - 实现文件读取：`readFile()`，带存在性检查
  - 实现文件复制：`copyFile()`，支持覆盖已有文件
  - 实现文件删除：`deleteFile()`
  - 所有方法完善异常处理
- `DocumentService`
  - `listDocuments()`：返回文档列表，包含编辑状态信息
  - `uploadDocument()`：接收文件内容，保存到 `data/documents` 并记录元数据，增加文件类型验证
  - `downloadDocument()`：读取文件字节返回给客户端
  - `viewDocument()`：支持文本文件预览，非文本文件提示
  - `saveDocument()`：校验编辑权限，保存文件并更新元数据
  - `isAllowedFileType()`：验证文件类型是否允许上传
- `ClientApp`
  - 完善 `uploadDocument()`：读取本地文件字节发送
  - 完善 `downloadDocument()`：接收文件并保存到本地，支持目录自动命名
  - 完善 `viewDocument()`：显示文本文件预览
  - 完善 `saveDocument()`：读取本地文件发送保存

## 2. 文档管理模块行为

### 文档存储约定

- 文件存储目录：`data/documents`
- 文件名格式：`{documentId}_{originalFileName}`
- 文档元数据保存在内存 `ConcurrentHashMap` 中

### 允许的文件类型

代码类：
- `.c`, `.cpp`, `.h`, `.hpp`, `.java`, `.py`, `.js`, `.ts`, `.jsx`, `.tsx`, `.go`, `.php`, `.rb`, `.rs`, `.swift`, `.vue`

网页样式：
- `.html`, `.htm`, `.css`, `.scss`, `.less`, `.svg`

配置数据：
- `.txt`, `.json`, `.yml`, `.yaml`, `.ini`, `.conf`, `.cfg`, `.xml`, `.csv`, `.log`, `.env`

脚本命令：
- `.sh`, `.bat`, `.cmd`, `.ps1`, `.bash`

文档标记：
- `.md`, `.sql`, `.graphql`

工程文件（无扩展名）：
- `Dockerfile`, `Makefile`, `.gitignore`

其他类型文件上传时会报错："不支持的文件类型，请上传代码、网页样式、配置数据、脚本命令、文档标记或工程文件"

### 上传流程

1. 客户端读取本地文件为 `byte[]`
2. 客户端发送包含 `fileName` 和 `fileContent` 的 `Map` payload
3. 服务端生成文档ID（格式：`D-数字`）
4. 服务端保存文件到 `data/documents/{documentId}_{fileName}`
5. 服务端记录文档元数据

### 下载流程

1. 客户端发送文档ID
2. 服务端查找文档，读取文件字节
3. 服务端返回包含 `document` 和 `fileContent` 的 `Map`
4. 客户端可选保存到本地

## 3. 当前请求协议使用方式

### 上传文档请求

```
type = UPLOAD_DOCUMENT
username = 登录用户
payload = Map{
    "fileName": String (原始文件名),
    "fileContent": byte[] (文件内容)
}
```

### 下载文档响应

```
Response.data = Map{
    "document": Document (文档元数据),
    "fileContent": byte[] (文件内容)
}
```

### 查看文档响应

```
Response.data = Map{
    "document": Document (文档元数据),
    "preview": String (文档预览内容),
    "isTextFile": Boolean (是否文本文件)
}
```

### 保存文档请求

```
type = SAVE_DOCUMENT
username = 登录用户
documentId = 文档ID
payload = byte[] (文件内容)
```

### 文本文件判断

当前通过扩展名判断文本文件：
- `.txt`, `.md`, `.csv`, `.xml`, `.json`, `.html`, `.css`, `.js`
- 其他扩展名视为非文本文件

## 4. 联调说明

### 与成员 A 的对接点

- 服务端已实现多线程安全的文档管理
- 使用 `ConcurrentHashMap` 管理内存文档数据
- `LockService` 集成到 `DocumentService` 中进行权限校验

### 与成员 D 的对接点

- 上传协议已确定为 `Map` 结构，包含 `fileName` 和 `fileContent`
- 下载响应为 `Map` 结构，包含 `document` 和 `fileContent`
- 查看文档响应为 `Map` 结构，包含 `document`, `preview` 和 `isTextFile`
- 保存文档直接发送 `byte[]` 文件内容
- 客户端已完成对应功能的对接

### 与成员 C 的对接点

- 上传、保存时可调用 `VersionService` 生成版本（当前未集成）
- 文档ID格式为 `D-数字`，版本ID为 `V-数字`，便于关联
- `FileStorage` 已提供文件复制功能，可用于版本备份

## 5. 当前已知限制

- 文档元数据仅保存在内存中，服务重启后会丢失
- 暂未集成版本生成逻辑
- 编辑锁释放后未通知其他等待用户
- 文本文件扩展名列表可进一步扩展
- 大文件上传可能有内存压力（当前全量读取）

## 6. 手工测试建议

### 启动流程

1. 启动服务端：
```bash
java -cp target/classes com.sharedoc.server.ServerMain
```

2. 启动客户端：
```bash
java -cp target/classes com.sharedoc.client.ClientMain
```

### 建议测试场景

1. 使用 `admin / 123456` 登录
2. 上传本地文件（如 `README.md`）
3. 尝试上传不支持的文件（如 `.exe`, `.zip`），应报错提示不支持
4. 尝试上传工程文件（如 `Dockerfile`, `Makefile`, `.gitignore`），应成功
5. 查看文档列表，确认文档已上传
6. 使用文档ID查看文档预览（文本文件应显示内容）
7. 下载文档到本地，验证文件完整性
8. 申请编辑权限，尝试保存修改后的文件
9. 使用另一个用户登录，验证编辑权限互斥
10. 测试下载时只输入目录，应自动使用原始文件名

### 文件验证

- 检查 `data/documents` 目录下是否正确保存上传的文件
- 验证下载的文件与源文件内容一致
- 确认文档元数据（上传时间、修改时间等）正确

## 7. 后续建议

- 成员 C 可在 `DocumentService.uploadDocument()` 和 `saveDocument()` 中调用 `VersionService` 生成版本
- 后续可增加文档元数据持久化（如保存到 JSON 或数据库）
- 可增加文件大小限制和安全检查
- 可扩展文本文件预览的截断策略和编码检测
- 可增加编辑锁超时机制
