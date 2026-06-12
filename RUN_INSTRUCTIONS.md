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
A: 后端会把当前文件写入 `data/documents/`，把历史版本写入 `data/versions/`。请确认 Java 进程对项目目录有读写权限。

**Q: `data/versions/` 里为什么有 `.patch.json` 文件？**  
A: 初始上传和回滚版本会保存完整文件；普通编辑版本只保存本次修改片段。短时间内同一用户的多次保存会合并到同一个 `*.patch.json` 中，通过 `patchCount` 记录片段数量，用来减少版本存储空间。

**Q: 登出后别人为什么还不能编辑？**  
A: 正常点击前端登出时，`UserService.logout` 会自动释放当前用户持有或排队中的区间锁。如果是浏览器异常关闭，当前项目还没有实现更完整的会话超时或断线回收机制。

**Q: 多个用户能不能同时编辑同一份文档？**  
A: 可以，只要编辑区间不重叠。前端会按行申请细粒度区间锁，重叠区间会进入等待队列；任一用户保存后，其他在线用户会通过 SSE 收到局部内容更新。

**Q: 回滚版本时其他用户页面会怎样？**  
A: 回滚要求当前文档没有活动或排队区间锁。回滚成功后服务端会发送 `document-rolled-back` 事件，其他在线用户会刷新当前文档内容，避免继续基于旧内容编辑。

**Q: 历史版本弹窗能看到哪些改动？**  
A: 点击历史版本表格行后，前端会请求 `/versions/{versionId}/diff`，展示该版本相对上一个版本的新增、删除或替换内容。合并后的 PATCH 版本会展示其中每个修改片段。

**Q: 为什么重启服务后文档列表或登录状态变化了？**  
A: 用户在线状态、编辑锁和内存中的业务状态不会持久化。文档文件和版本文件仍保留在 `data/` 目录下。
