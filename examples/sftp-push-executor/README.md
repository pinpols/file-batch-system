# SFTP Push Executor — 第三方插件示范

P0 Phase 4 示范:**业务方如何在主项目外开发 `BatchTaskExecutor` 插件 jar**。

参考 [task-spi-design.md](../../docs/design/task-spi-design.md) §Phase 4。

## 形态

- 独立 Maven module(**不挂主 reactor**),自己有 pom + version
- 唯一硬依赖:`batch-common`(`provided` scope,运行时由 worker classpath 提供)
- 不引 `batch-worker-core` / Spring / 任何 framework
- 通过 `META-INF/services/com.example.batch.common.spi.task.BatchTaskExecutor` 注册,JDK `ServiceLoader` 自动发现

## 构建

```bash
mvn install -f examples/sftp-push-executor/pom.xml
```

产出:`examples/sftp-push-executor/target/sftp-push-executor-1.0.0-SNAPSHOT.jar`

## 部署 / 启用

1. 把 jar 放进任一 worker(import/export/process/dispatch/core)的 `lib/` 或 classpath
2. worker 启动时 `BatchTaskExecutorRegistry` 通过 ServiceLoader 自动注册 `sftp_push` taskType
3. 在 console 新建 job 时 `taskType="sftp_push"` 即可派发到该 worker

## job 参数协议

```json
{
  "taskType": "sftp_push",
  "parameters": {
    "host": "sftp.partner.com",
    "port": 22,
    "username": "batch-user",
    "password": "***",
    "localPath": "/var/batch/out/dispatch-20260530.csv",
    "remotePath": "/inbox/dispatch-20260530.csv"
  }
}
```

`password` 跟 `privateKey` 二选一。

## 当前状态:Stub

`SftpPushTaskExecutor.doSftpPush()` 是 mock —— 校验参数 + 返成功元数据但**不实际传输**。

真正实现请参考类 Javadoc 里的 JSch 示例代码片段:加 `com.jcraft:jsch:0.1.55` 依赖,替换 `doSftpPush()` 即可。

## 业务方按本模板新开插件

1. 复制本 module 改 `artifactId` / 包名 / class 名
2. 实现 `BatchTaskExecutor` 三个方法(`taskType` / `capability` / `execute`)
3. 在 `META-INF/services/com.example.batch.common.spi.task.BatchTaskExecutor` 写自己实现类全名
4. 加业务依赖到 pom(注意 worker 已有的 jar 用 `provided` 避免冲突)
5. `mvn install`,jar 部署到 worker classpath

完。无须改主项目代码,无须发 PR 到主仓库。

## 安全审计建议(业务插件 review checklist)

- [ ] `execute()` 不调 `System.exit` / `Runtime.exec`(除非已通过本仓 ShellTaskExecutor 安全约束)
- [ ] 不读 `System.getenv` 暴露租户隔离应有的环境变量
- [ ] 不直连 DB(走 `TaskContext` 注入的 dataSource;本模板暂不提供,后续 PR 加)
- [ ] capability 字段诚实(idempotent / cancellable / resourceKinds 跟实际行为一致)
- [ ] timeout 守护:实现自己的 `recommendedTimeout` 兜底,不要无限阻塞
