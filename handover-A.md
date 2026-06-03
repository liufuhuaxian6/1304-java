# 成员 A 交接说明

## 1. 本次已完成内容

本次交付聚焦成员 A 负责的服务器与多线程部分，补齐了服务端连接管理、请求分发、连接级清理与基础异常兜底，并对 `UserService` / `DocumentService` 做了最小配套修正，确保其能和成员 B、D 当前已实现的协议联通。

已完成：

- `ServerMain`
  - 启动时自动创建 `data/documents` 和 `data/versions` 目录
  - 服务端改为“共享服务实例 + 固定线程池”模型
  - 线程池大小固定为 `max(4, CPU核数 * 2)`
  - 客户端连接统一提交到线程池处理，不再为每个连接直接 `new Thread(...)`
  - 增加线程池关闭逻辑与关闭钩子
- `ClientHandler`
  - 改为构造注入共享的 `UserService` / `DocumentService` / `VersionService`
  - 保持“一次请求，一次响应”的同步对象流处理模式
  - 完成 `RequestType` 到业务服务的分发
  - 对非法对象、空请求类型、业务运行时异常统一返回失败响应
  - 记录连接级登录用户名
  - 客户端断开或异常退出时自动触发登出清理
  - 支持登录后复用连接内记录的用户名处理后续请求
- `DocumentService`
  - `listDocuments()` 统一返回 `List<Document>`，与成员 D 当前客户端展示逻辑对齐
  - `requestEdit()` 先校验文档是否存在，再尝试加锁
  - `saveDocument()` 修复空指针风险，改为安全校验锁持有者
  - `releaseEdit()` 对文档不存在或非锁持有者返回明确失败
  - 增加 `releaseAllEditsByUser()` 供登出和断连清理复用
  - `listDocuments()` 返回前同步文档当前编辑状态
- `UserService`
  - `login()` 增加用户名、密码判空与密码校验
  - `logout()` 调用 `DocumentService.releaseAllEditsByUser()`，避免遗留编辑锁
  - 继续沿用 demo 用户：`admin / 123456`、`user / 123456`

## 2. 当前服务端行为

### 连接处理模型

- 服务端监听端口：`8888`
- 每个客户端连接由一个 `ClientHandler` 负责
- `ClientHandler` 本身按同步顺序处理请求：收到一个 `Request`，返回一个 `Response`
- 多个客户端之间通过线程池并发处理
- 当前不支持服务端主动推送、广播或长连接事件通知

### 连接级清理行为

- 登录成功后，`ClientHandler` 会记录本连接对应的 `currentUsername`
- 收到 `LOGOUT` 且成功后，会清空当前连接登录状态
- 若客户端异常断开或直接关闭 socket：
  - 服务端会自动执行 `logout(currentUsername)`
  - 若该用户持有文档编辑锁，会一并释放

### 请求异常处理

- 若收到的对象不是 `Request`，服务端返回：
  - `Response.fail("Unsupported request object.")`
- 若 `request == null` 或 `request.getType() == null`，服务端返回：
  - `Response.fail("Request type is required.")`
- 若业务处理过程中抛出运行时异常，服务端返回：
  - `Response.fail("服务器处理请求时发生异常: ...")`
- 单次坏请求不会直接打断整个连接，客户端仍可继续发送后续合法请求

## 3. 当前请求协议使用方式

当前继续沿用项目已有的 `Request` / `Response` Java Serializable 对象协议，不新增字段。

### 服务端分发约定

- 登录
  - `type = LOGIN`
  - `username = 用户名`
  - `payload = 密码字符串`
- 登出
  - `type = LOGOUT`
  - `username = 当前登录用户`
- 文档列表
  - `type = LIST_DOCUMENTS`
  - `Response.data = List<Document>`
- 上传文档
  - `type = UPLOAD_DOCUMENT`
  - `username = 登录用户`
  - `payload = Map{ "fileName": String, "fileContent": byte[] }`
- 下载文档
  - `type = DOWNLOAD_DOCUMENT`
  - `documentId = 文档 ID`
  - `Response.data = Map{ "document": Document, "fileContent": byte[] }`
- 查看文档
  - `type = VIEW_DOCUMENT`
  - `documentId = 文档 ID`
  - `Response.data = Map{ "document": Document, "preview": String, "isTextFile": Boolean }`
- 申请编辑
  - `type = REQUEST_EDIT`
  - `documentId = 文档 ID`
- 保存文档
  - `type = SAVE_DOCUMENT`
  - `documentId = 文档 ID`
  - `payload = byte[]`
- 释放编辑
  - `type = RELEASE_EDIT`
  - `documentId = 文档 ID`
- 版本相关
  - `LIST_VERSIONS`、`DOWNLOAD_VERSION`、`ROLLBACK_VERSION` 继续分发到 `VersionService`
  - 当前成员 A 未扩展版本模块的真实业务实现

### 用户名回填约定

- 除 `LOGIN` 外，如果请求里 `username` 为空，`ClientHandler` 会优先尝试使用当前连接里最近一次成功登录的用户名
- 该行为主要用于兼容客户端复用同一连接时的状态传递

## 4. 联调说明

### 与成员 B 的对接点

- 服务端已稳定接入成员 B 当前文档协议：
  - 上传：`Map{fileName, fileContent}`
  - 下载：`Map{document, fileContent}`
  - 查看：`Map{document, preview, isTextFile}`
  - 保存：`byte[]`
- `DocumentService.listDocuments()` 已统一为 `List<Document>`，方便客户端直接展示
- 文档元数据仍保存在内存 `ConcurrentHashMap` 中，线程间共享

### 与成员 D 的对接点

- 客户端默认连接 `localhost:8888`
- 仍使用 `ObjectOutputStream` / `ObjectInputStream`
- 当前交互模式保持为“客户端发送请求，服务端返回一个响应”
- 登录后若客户端复用同一连接，服务端可根据连接上下文补齐缺失用户名
- 文档列表返回值已统一为 `List<Document>`，与成员 D 当前展示逻辑一致

### 与成员 C 的对接点

- `ClientHandler` 已预留并接入 `VersionService` 请求分发
- 当前版本模块请求能够到达 `VersionService`
- 成员 C 后续只需继续完善：
  - 历史版本生成
  - 历史版本下载
  - 版本回滚
- 若成员 C 后续在登出、断线、版本回滚等场景加入更多锁处理或版本校验逻辑，可复用当前连接清理和请求分发链路

## 5. 当前已知限制

- 服务端日志目前仍以 `System.out.println` / `System.err.println` 为主，未引入统一日志组件
- 当前线程池大小为固定值，不支持动态调整
- 当前连接模型仍为同步请求响应，不支持服务端主动推送
- 文档和在线用户元数据主要保存在内存中，服务重启后状态会丢失
- 版本相关真实业务逻辑仍由成员 C 后续补全
- 编辑锁机制当前不包含超时、等待队列、抢占或重入策略

## 6. 测试情况与手工测试建议

### 自动化测试

当前已补充并通过 Maven 测试：

- `ClientHandlerTest`
  - 非法对象请求后连接仍可继续使用
  - 空 `RequestType` 请求返回失败且连接不中断
  - 客户端断连后编辑锁自动释放
  - 已登录连接可复用连接级用户名状态
- `DocumentServiceTest`
  - 上传后文档列表返回 `List<Document>`
  - 下载内容与上传内容一致
  - 文本预览内容正确
  - 持锁保存后再次下载可获得更新内容
  - 文档编辑状态在列表中能正确反映
  - 非锁持有者无法保存或释放编辑权限
  - 第二个用户无法抢占正在编辑中的文档
- `UserServiceTest`
  - 正确密码登录成功
  - 错误密码登录失败
  - 登出时会释放当前用户持有的编辑锁

执行命令：

```bash
mvn test
```

最近一次结果：

- `Tests run: 14`
- `Failures: 0`
- `Errors: 0`
- `BUILD SUCCESS`

### 手工测试建议

1. 启动服务端：`java -cp target/classes com.sharedoc.server.ServerMain`
2. 启动两个客户端：`java -cp target/classes com.sharedoc.client.ClientMain`
3. 使用 `admin / 123456` 登录并上传一个文本文件
4. 查看文档列表，确认能直接显示 `Document` 信息而不是 `Map.toString()`
5. 使用第一个客户端申请编辑权限
6. 使用第二个客户端申请同一文档编辑权限，应提示失败
7. 关闭第一个客户端，不手动释放编辑权限
8. 再次使用第二个客户端申请编辑权限，应成功
9. 保存文档后重新下载或查看，确认内容已更新
10. 发送异常请求或不完整请求，确认服务端不会因单次请求中断整个连接

## 7. 后续建议

- 若后续客户端需要多人在线提示、锁等待提示或广播通知，可在当前 `ClientHandler` 基础上扩展异步读取/推送模型
- 若后续服务端要增强稳定性，建议引入统一日志组件并补充线程池运行状态输出
- 若文档量增加，建议把在线用户、文档元数据和锁状态从纯内存结构扩展为可持久化方案
- 成员 C 完成版本模块后，建议补一轮“上传 -> 编辑 -> 版本列表 -> 下载版本 -> 回滚版本”的端到端联调测试
