# batch-test-support

该模块集中存放跨模块复用的 Testcontainers、故障注入和 ArchUnit 测试支撑代码。

- 业务模块只能以 `test` scope 依赖它。
- 应用运行时 classpath 不得包含该 artifact。
- 共享支撑类放在 `src/main/java`，以便消费模块的测试源码引用。
- 本模块自身的测试用于验证 `batch-common` 集成能力和故障注入基线。

运行应用镜像打包时可使用 `-Dmaven.test.skip=true`；正常 CI 测试门禁仍必须编译并执行测试。
