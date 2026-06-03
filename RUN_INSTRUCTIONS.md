# 共享文档管理与版本控制系统 - 运行说明

## 1. 账号信息说明

系统当前使用内存数据来模拟用户账号，服务重启后会重置状态。账号信息硬编码在 `src/main/java/com/sharedoc/service/UserService.java` 文件中。

**默认可用测试账号**：
- 用户名：`admin`，密码：`123456` （管理员）
- 用户名：`user`，密码：`123456` （普通用户）

> **注意**：如果在前端登录时遇到问题，请确保使用上述账号和密码组合，并且保证后端服务（8082端口的HTTP API）正在运行。

---

## 2. 后端启动说明

后端项目基于 Java 17 和 Maven 构建。启动后端服务不仅会开启原有的 Socket 服务（8889端口），还会同时开启为 Web 前端提供的 HTTP API 服务（8082端口）。

### 启动步骤

1. 编译项目：
   ```bash
   mvn clean compile
   ```

2. 运行服务端：
   ```bash
   mvn exec:java -Dexec.mainClass="com.sharedoc.server.ServerMain"
   ```

---

## 3. 前端启动说明

前端采用了轻量级的 HTML + Vue3(CDN) + TailwindCSS(CDN) 方案，所有的代码都在 `frontend` 目录下的 `index.html` 和 `app.js` 中。

你只需要一个简单的静态文件服务器来提供前端页面的访问。

### 启动步骤

1. 打开一个**新终端**，进入前端目录：
   ```bash
   cd frontend
   ```

2. 使用 Python 启动一个简单的 HTTP 服务器（Ubuntu / Mac 默认自带 Python3）：
   ```bash
   python3 -m http.server 8003
   ```

### 访问系统

在浏览器中打开：[http://localhost:8003/](http://localhost:8003/) 即可看到系统登录界面。使用前文提到的账号即可登录使用。

---

## 4. 常见问题 (FAQ)

**Q: 为什么上传/保存失败？**
A: 后端文件默认存储在项目根目录的 `data/documents/` 文件夹下。如果运行报错，通常是因为权限问题或者文件夹不存在。请确保 Java 进程对该目录有读写权限。

**Q: 点击登出后，为什么别人依然无法编辑该文档？**
A: 我们已经在 `UserService.logout` 中加入了释放锁的逻辑 `documentService.releaseAllEditsByUser(username)`，正常登出会自动释放锁。如果是直接关闭浏览器，目前后端还没有实现 Socket 级别或 Session 级别的超时检测机制，这是在“后续开发计划”中的一环。