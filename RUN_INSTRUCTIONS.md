# 共享文档系统运行说明

## 1. 账号说明

系统当前使用内存用户数据，服务重启后会重置。

默认可用账号：

- `admin / 123456`
- `user / 123456`

账号定义位置：

- `src/main/java/com/sharedoc/service/UserService.java`

## 2. 启动后端

项目基于 Java 17 和 Maven。

编译：

```bash
mvn clean compile
```

启动：

```bash
mvn exec:java -Dexec.mainClass="com.sharedoc.server.ServerMain"
```

后端启动后会提供：

- HTTP API: `http://localhost:8082`

## 3. 启动前端

前端代码位于 `frontend/`，是静态 HTML 页面。

进入目录：

```bash
cd frontend
```

启动静态服务，例如：

```bash
python -m http.server 8003
```

浏览器访问：

```text
http://localhost:8003/
```

前端默认请求后端地址：

```text
http://localhost:8082/api/v1
```

## 4. 常见问题

**Q: 上传或保存失败怎么办？**  
A: 后端会把文件写入 `data/documents/` 和 `data/versions/`。请确认 Java 进程对项目目录有读写权限。

**Q: 登出后别人为什么还不能编辑？**  
A: 正常点击前端登出时，`UserService.logout` 会自动释放当前用户持有的编辑锁。如果是浏览器异常关闭，当前项目还没有实现更完整的会话超时或断线回收机制。

**Q: 为什么重启服务后文档列表或登录状态变化了？**  
A: 用户在线状态、编辑锁和内存中的业务状态不会持久化。文档文件和版本文件仍保留在 `data/` 目录下。
