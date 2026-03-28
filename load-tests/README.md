# 压测模块说明

这里收纳 `load-tests/` 的 Gatling 压测脚本和配置入口。

## 目录职责

- 该模块只负责压测，不参与主工程的常规编译链
- 所有压测参数统一收敛到 [GatlingConfig.java](./src/test/java/com/example/batch/loadtest/GatlingConfig.java)
- 所有场景脚本放在 `src/test/java/com/example/batch/loadtest/simulations/`

## 场景列表

- [JobLaunchSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/JobLaunchSimulation.java) - 触发链路写压测
- [ConsoleQuerySimulation.java](./src/test/java/com/example/batch/loadtest/simulations/ConsoleQuerySimulation.java) - 控制台查询读压测
- [CapacityBaselineSimulation.java](./src/test/java/com/example/batch/loadtest/simulations/CapacityBaselineSimulation.java) - 混合读写容量基线

## 运行方式

进入 `load-tests/` 目录后执行：

```bash
mvn gatling:test -Dsimulation=JobLaunchSimulation
mvn gatling:test -Dsimulation=ConsoleQuerySimulation
mvn gatling:test -Dsimulation=CapacityBaselineSimulation
```

也可以通过 Maven profile 切换目标环境：

- `local`：默认 profile，指向本地联调端点
- `staging`：指向 staging 环境
- `prod-probe`：保守的生产容量探测 profile

示例：

```bash
mvn -Pstaging gatling:test -Dsimulation=JobLaunchSimulation
```

## 常用系统属性

### 端点

- `-Dtrigger.baseUrl=http://localhost:8081`
- `-Dconsole.baseUrl=http://localhost:8080`

### 测试数据

- `-DtenantId=t1`
- `-DjobCode=E2E_IMPORT_LOAD`
- `-DbizDate=2026-01-15`

### 压测参数

- `-Dusers.peak=20`
- `-Dduration.seconds=120`
- `-Dramp.seconds=30`

### SLO 阈值

- `-Dslo.write.p95ms=500`
- `-Dslo.read.p99ms=300`
- `-Dslo.maxErrorPct=1.0`

### 控制台认证

- `-Dconsole.authToken=Bearer <token>`

## 与文档的对应关系

- [压测种子数据说明](../docs/sql/load-test/README.md)
- [容量基线模板](../docs/testing/load-test-capacity-baseline.md)
- [测试文档索引](../docs/testing/README.md)

## 备注

- `GatlingConfig` 负责读取系统属性并提供统一默认值
- 仓库根 `pom.xml` 不包含该模块，需要在 `load-tests/` 下单独执行
