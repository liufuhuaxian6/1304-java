# 成员 C 交接说明

## 1. 本次已完成内容

本次交付聚焦成员 C 负责的编辑锁与版本管理部分，在成员 B 的真实文件上传/保存基础上补齐历史版本生成、查看、下载和可选回滚能力。

已完成：

- `LockService`
  - 保留 `ConcurrentHashMap<String, String>` 管理 `documentId -> username` 的编辑锁关系
  - `tryLockDocument()` 继续保证同一文档同一时刻只有一个编辑用户
  - `unlockDocument()` 改为返回释放结果，便于上层判断是否为持锁用户
- `DocumentService`
  - 上传文档成功后自动调用 `VersionService.createInitialVersion()` 生成初始版本
  - 保存文档成功后自动调用 `VersionService.createEditVersion()` 生成编辑版本
  - 申请编辑权限前校验文档是否存在，成功后更新 `editingUser` 和 `editingStartTime`
  - 保存文档前校验当前用户是否持有该文档编辑锁
  - 释放编辑权限时校验释放者是否为持锁用户
  - 新增 `rollbackDocumentToVersion()`，回滚前要求当前用户先持有该文档编辑锁
- `VersionService`
  - 版本元数据使用静态 `ConcurrentHashMap` 保存，多个客户端处理线程之间可共享
  - 生成 `UPLOAD`、`EDIT`、`ROLLBACK` 三类版本记录
  - `listVersions()` 返回指定文档的历史版本列表
  - `downloadVersion()` 根据版本 ID 读取历史版本文件字节
  - `rollbackToVersion()` 将历史版本文件复制回当前文档路径，并生成新的回滚版本记录
- `VersionStorage`
  - 实现版本文件复制落盘
  - 实现历史版本文件读取
  - 实现历史版本恢复到当前文档路径
  - 对版本文件名做基础清理，避免原始文件名影响目录结构
- `ClientHandler`
  - `ROLLBACK_VERSION` 改由 `DocumentService.rollbackDocumentToVersion()` 处理，以便校验编辑锁并拿到当前文档路径

## 2. 编辑锁模块行为

### 申请编辑权限

1. 客户端发送 `REQUEST_EDIT`，携带文档 ID 和当前用户名。
2. 服务端先确认用户已登录且文档存在。
3. 若文档未被锁定，则记录 `documentId -> username`。
4. 服务端同步更新文档元数据：
   - `editingUser = username`
   - `editingStartTime = 当前时间`

### 保存文档

保存前必须满足：

- 文档存在
- 当前用户等于该文档编辑锁持有者
- payload 为 `byte[]`，或为包含 `fileContent` 的 `Map`

保存成功后：

- 覆盖当前文档文件
- 更新 `lastModifiedTime`
- 自动生成一个 `EDIT` 类型历史版本

### 释放编辑权限

只有当前持锁用户可以释放编辑权限。释放成功后：

- 删除锁关系
- 清空文档元数据中的 `editingUser`
- 清空文档元数据中的 `editingStartTime`

## 3. 版本管理模块行为

### 版本文件存储约定

- 版本文件根目录：`data/versions`
- 单文档版本目录：`data/versions/{documentId}`
- 版本文件名格式：`{versionId}-{fileName}`
- 示例：`data/versions/D-1/V-2-README.md`

### 版本元数据

每条历史版本记录使用 `DocumentVersion`：

- `versionId`：版本 ID，格式为 `V-数字`
- `documentId`：所属文档 ID
- `fileName`：原始文件名
- `editor`：操作用户
- `editTime`：版本生成时间
- `operationType`：`UPLOAD`、`EDIT` 或 `ROLLBACK`
- `versionPath`：历史版本文件路径
- `comment`：版本备注

### 自动生成版本的时机

- 上传文档成功后生成 `UPLOAD` 初始版本
- 保存文档成功后生成 `EDIT` 编辑版本
- 回滚文档成功后生成 `ROLLBACK` 回滚版本

### 回滚规则

1. 用户必须先申请该文档编辑权限。
2. 服务端确认目标版本存在，并且属于当前文档。
3. 服务端将历史版本文件复制回当前文档路径。
4. 服务端更新文档最后修改时间。
5. 服务端生成一个新的 `ROLLBACK` 版本，备注为 `回滚到版本 {versionId}`。

## 4. 当前请求协议使用方式

### 查看历史版本

```
type = LIST_VERSIONS
username = 当前登录用户
documentId = 文档ID
payload = null
```

响应：

```
Response.data = List<DocumentVersion>
```

### 下载历史版本

```
type = DOWNLOAD_VERSION
username = 当前登录用户
payload = 版本ID
```

响应：

```
Response.data = Map{
    "version": DocumentVersion,
    "fileContent": byte[]
}
```

### 回滚历史版本

```
type = ROLLBACK_VERSION
username = 当前登录用户
documentId = 文档ID
payload = 版本ID
```

响应：

```
Response.data = Map{
    "document": Document,
    "rolledBackFrom": DocumentVersion,
    "rollbackVersion": DocumentVersion
}
```

### 保存文档生成版本

当前兼容两种 payload：

```
payload = byte[]
```

或：

```
payload = Map{
    "fileContent": byte[],
    "comment": String
}
```

客户端当前发送的是 `byte[]`，服务端会使用默认备注 `编辑保存版本`。

## 5. 联调说明

### 与成员 A 的对接点

- `ClientHandler` 的 `LIST_VERSIONS` 和 `DOWNLOAD_VERSION` 仍直接调用 `VersionService`
- `ROLLBACK_VERSION` 需要文档当前路径和锁校验，因此改为调用 `DocumentService`
- 版本元数据为进程内静态共享，多客户端线程能看到同一份历史版本列表

### 与成员 B 的对接点

- `DocumentService.uploadDocument()` 上传落盘成功后会自动生成初始版本
- `DocumentService.saveDocument()` 保存落盘成功后会自动生成编辑版本
- `VersionStorage` 复用 `FileStorage.copyFile()` 和 `FileStorage.readFile()`
- 文档当前文件仍存储在 `data/documents`，历史版本文件存储在 `data/versions`

### 与成员 D 的对接点

- 历史版本列表返回 `List<DocumentVersion>`，客户端已有格式化展示能力
- 历史版本下载现在返回 `Map{version, fileContent}`，客户端后续可参照当前文档下载逻辑保存到本地
- 回滚版本前客户端需要先调用申请编辑权限，否则服务端会返回失败
- 回滚成功后服务端返回 `Map`，客户端当前会打印 `toString()`，后续可增加专门展示逻辑

## 6. 当前已知限制

- 文档元数据和版本元数据仍保存在内存中，服务端重启后会丢失
- 已落盘的版本文件不会在服务端重启后自动重建为版本列表
- 版本 ID 使用全局递增序列，不是每个文档从 1 开始的版本号
- 回滚要求用户先持有编辑锁，但回滚成功后不会自动释放锁
- 历史版本下载服务端已返回字节，客户端暂未实现本地保存
- 暂未实现锁超时、等待队列、强制释放或用户登出自动释放全部锁

## 7. 手工测试建议

### 启动流程

1. 编译项目：

```bash
mvn clean compile
```

2. 启动服务端：

```bash
java -cp target/classes com.sharedoc.server.ServerMain
```

3. 启动客户端：

```bash
java -cp target/classes com.sharedoc.client.ClientMain
```

### 建议测试场景

1. 使用 `admin / 123456` 登录。
2. 上传一个文本文件，例如 `README.md`，应生成初始版本。
3. 查看文档列表，记录返回的文档 ID。
4. 查看历史版本，应能看到一个 `UPLOAD` 类型版本。
5. 申请该文档编辑权限。
6. 使用修改后的本地文件保存文档，应生成 `EDIT` 类型版本。
7. 再次查看历史版本，应至少看到 `UPLOAD` 和 `EDIT` 两条记录。
8. 使用另一个用户尝试申请同一文档编辑权限，应提示文档正在被当前用户编辑。
9. 在持有编辑锁的情况下回滚到初始版本，应生成 `ROLLBACK` 类型版本。
10. 下载当前文档并检查内容是否已恢复为目标历史版本内容。

### 文件验证

- 检查 `data/versions/{documentId}` 目录是否生成历史版本文件
- 检查历史版本文件内容是否与对应上传/保存时的文件一致
- 回滚后检查 `data/documents/{documentId}_{fileName}` 是否被恢复为目标版本内容

## 8. 本次验证情况

已执行：

```bash
mvn clean compile
```

结果：构建成功。

## 9. 后续建议

- 成员 D 可补齐历史版本下载的本地保存逻辑，按 `Map{version, fileContent}` 处理即可
- 后续可将版本元数据持久化到 JSON 或数据库，避免服务端重启后历史列表丢失
- 后续可增加文档级版本序号，例如 `D-1` 下显示 `v1`, `v2`, `v3`
- 用户模块完善登出时，可调用锁服务释放该用户持有的所有编辑锁
- 如需更严格一致性，可在保存或回滚生成版本失败时增加事务式补偿逻辑
