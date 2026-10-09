# File Checksum Executor

独立的第三方 `BatchTaskExecutor` 插件示例，不依赖 `batch-worker-core`、Spring 或主项目 reactor。实现只读 SHA-256 计算，适合验证 ServiceLoader 发现、能力注册和任务执行链路。

## 构建与验证

```bash
mvn install -f examples/task-spi-plugin/file-checksum-executor/pom.xml
mvn test -f examples/task-spi-plugin/file-checksum-executor/pom.xml
```

`batch-common` 使用 `provided` scope，由 Worker 运行时提供。把生成的 JAR 放入 `worker-atomic` 的插件 classpath 后，启动注册应出现 `file_sha256`，Console 能看到 `DISK`、幂等、取消能力和建议超时信息。

## 参数与结果

任务参数为 `inputPath`，必须是 Worker 可读的常规文件路径。成功结果包含 `sha256` 和 `bytesRead`。示例不创建目录、不写文件、不访问网络。

文件路径由任务提交方控制时，必须通过 Worker 沙箱、允许目录和租户授权限制可读范围；插件代码不是文件访问隔离边界。不要把该示例当作多租户文件访问的安全沙箱。
