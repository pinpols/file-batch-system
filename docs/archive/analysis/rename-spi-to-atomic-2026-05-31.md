# 重命名计划:`batch-worker-spi` → `batch-worker-atomic`(spi → atomic)

> 2026-05-31 立。目标:把"那个跑 sql/stored-proc/http 原子任务的 worker"从机制名 `spi` 改成能力名 `atomic`。
>
> **不动的东西**:`BatchTaskExecutor` / `TaskContext` / `TaskResult` 这套 SPI 契约保留 "SPI" 命名(它名副其实,是 Service Provider Interface,8 个 worker 都实现它)。`batch.worker.executors.{sql,stored-proc,http}.*` 子执行器前缀保留(不含 "spi")。`taskType` 值 `sql`/`stored_proc`/`http` 不变。

## 命名映射(总表)

| 维度 | 现在 | 改为 |
|---|---|---|
| Maven 模块目录 / artifactId | `batch-worker-spi` | `batch-worker-atomic` |
| Java 包根 | `io.github.pinpols.batch.worker.spi.*` | `io.github.pinpols.batch.worker.atomic.*` |
| 配置前缀(身份) | `batch.worker.spi.*` | `batch.worker.atomic.*` |
| 子执行器前缀 | `batch.worker.executors.*` | **不变** |
| `JobType` 枚举值 | `SPI` | `ATOMIC` |
| worker_type | `SPI` | `ATOMIC` |
| Kafka 派发 topic | `batch.task.dispatch.spi` | `batch.task.dispatch.atomic` |
| Kafka consumer group | `batch-worker-spi` | `batch-worker-atomic` |
| taskType 值 | `sql`/`stored_proc`/`http` | **不变** |
| 主类 | `BatchWorkerSpiApplication` | `BatchWorkerAtomicApplication` |
| 配置/组件类 | `SpiWorkerConfiguration` `SpiIsolationStartupCheck` `SpiTaskConsumer` `BatchWorkerSpiProperties`(在 batch-common) | `AtomicWorkerConfiguration` `AtomicIsolationStartupCheck` `AtomicTaskConsumer` `BatchWorkerAtomicProperties` |
| e2e/测试类 | `SpiTaskPipelineE2eIT` `SpiMixedScenarioE2eIT` `SpiTaskLoadE2eIT` `E2eSpiApplication` `SpiSeedStrictVerifyE2eIT` `SpiWorkerConfigurationTest` `SpiIsolationStartupCheckTest` | 对应 `Atomic*` |

---

## Group A — 纯代码/配置(机械改,低风险,**无迁移**)

一个 PR 完成。建议用脚本 + `git mv`,改完 `mvn clean compile` + spotless。

- [ ] **模块**:`git mv batch-worker-spi batch-worker-atomic`;改其 `pom.xml` `<artifactId>`;根 `pom.xml` `<modules>` 条目。
- [ ] **包目录 + 声明**:`git mv` 包目录 `worker/spi` → `worker/atomic`(main + test);全仓 `sed` `io.github.pinpols.batch.worker.spi` → `io.github.pinpols.batch.worker.atomic`(package 声明 + 所有 import)。
- [ ] **类名**:按总表重命名(`BatchWorkerSpiApplication` 等);注意 `BatchWorkerSpiProperties` 在 **batch-common**,改它影响引用方,一并 sed。
- [ ] **配置 key**:`application.yml` / `application-local.yml`(worker 模块)+ `application-e2e.yml`(e2e)里 `batch.worker.spi.*` → `batch.worker.atomic.*`;**`batch.worker.executors.*` 不动**。
- [ ] **CLAUDE.md**:固定 10 模块清单 `batch-worker-spi` → `batch-worker-atomic`;ADR-029 注脚同步。
- [ ] **CI**:`full-ci-gate.yml`(IT shard 3 含 batch-worker-spi)、E2eIT shard 列表、`pr-gate.yml` 里模块名引用。
- [ ] **docker**:`docker/compose/app.yml`(image / `MODULE` arg / `container_name` / `LOGGING_FILE_NAME`);`docker/observability/prometheus-batch-rules.yml` 注释里的模块名。
- [ ] **scripts**:`start-all.sh` / `restart.sh` / `build-apps.sh`(模块名 + jar 名 + 端口 18087)、`07-spi-load.sh`(→ `07-atomic-load.sh`)、`strict-verify.sh` §7 注释。
- [ ] **build/runtime-jars**:`spi.jar` 之类产物命名(若脚本里硬编码)。
- [ ] **docs**:ADR-029 / ADR-035 / `p0-p1-p2-roadmap.md` / runbook 里**模块名 / job_type / topic** 的引用。
  - 注:`docs/design/task-spi-design.md` **文件名保留**(它讲的是 Task **SPI 机制**,机制名不改);只更新文内"那个 worker 模块"的称呼。

**验收**:`mvn clean test -pl batch-worker-atomic` 绿;全 reactor `mvn -DskipTests clean package` 绿;`grep -rn "worker.spi\|worker-spi" --include=*.java --include=*.yml`(排 target)只剩"SPI 机制/契约"相关命中(`BatchTaskExecutor` 那套),无模块残留。

---

## Group B — 写入数据库 / wire 值(**需迁移 + 兼容窗口,高风险**)

这组改的是持久化值和跨进程协议,不能纯 sed。**单独 PR,且与 Group A 解耦**(Group A 先合,Group B 走迁移流程)。

### B1. `JobType` 枚举值 `SPI` → `ATOMIC`(DictEnum,暴露 FE)
- [ ] `batch-common/.../enums/JobType.java`:`SPI("SPI",...)` → `ATOMIC("ATOMIC","原子任务")`。
- [ ] 它经 `ConsoleMetaQueryService` `EnumReg<>("jobType", JobType.class)` 暴露给 FE → **FE meta 刷新 + i18n**:`messages.properties` / `messages_zh_CN.properties` 里 `jobType.SPI` label → `jobType.ATOMIC`;`ConsoleMetaEnumRegistrationTest` 守护测试同步。
- [ ] FE `api.generated.ts` / 枚举下拉若有缓存,`npm run gen:api`。

### B2. DB CHECK 约束 + 存量数据迁移
- [ ] 新 Flyway 迁移 `V###__rename_spi_job_type_to_atomic.sql`:
  - `job_definition.job_type` / `job_task.task_type` 的 CHECK 白名单**加 `'ATOMIC'`**(过渡期 `SPI` 与 `ATOMIC` 并存);
  - `UPDATE` 存量行 `job_type='SPI'` → `'ATOMIC'`(及 `job_task.task_type='SPI'` 若有);
  - 切完再出后续迁移**移除 `'SPI'`**(两步,避免与旧实例并跑时约束拒绝)。
- [ ] `scripts/db/test-seed/platform_seed.sql` 里 `'SPI'` 种子数据 → `'ATOMIC'`。
- [ ] 旧迁移 `V156__add_spi_job_type.sql` **不改**(历史迁移不可变),靠新迁移覆盖。

### B3. Kafka topic `batch.task.dispatch.spi` → `.atomic`(**最高风险:in-flight 消息**)
- [ ] `BatchTopics.TASK_DISPATCH_SPI` 常量值 + 常量名(→ `TASK_DISPATCH_ATOMIC`)。
- [ ] orchestrator `BatchMqTopicsProperties.resolveDispatchTopic`:`worker_type="ATOMIC"` → `.atomic` 映射(过渡期保留 `"SPI"`→旧 topic 的兼容分支)。
- [ ] worker 消费配置:`application.yml` `topic: ${BATCH_TOPIC_DISPATCH_SPI:...}` → `BATCH_TOPIC_DISPATCH_ATOMIC:batch.task.dispatch.atomic`;`application-local.yml` 同步。
- [ ] topic 供给:`.env.example` / `.env.prod` 的 `KAFKA_TOPICS`(加 `.atomic`,过渡期两个都留);`validate-kafka-topics.sh` 同步;集群建 topic。
- [ ] **切换策略(关键)**:
  - 若环境有 live SPI 消息:**双订阅窗口** —— worker 过渡期同时消费 `.spi` + `.atomic`,orchestrator 先切 producer 到 `.atomic`,等旧 topic 排空再撤旧订阅 + 删旧 topic。
  - 若是 dev/本地无存量:可硬切(同一次部署 producer+consumer 一起换),省去双订阅。
- [ ] consumer group `batch-worker-spi` → `batch-worker-atomic`:新 group 从最新 offset 起(与新 topic 一起,无历史 offset 包袱)。

### B4. 测试里的 wire 断言
- [ ] `SpiWorkerConfigurationTest` / `BatchMqTopicsPropertiesTest`:断言值 `batch.task.dispatch.spi` / `"SPI"` → `.atomic` / `"ATOMIC"`(过渡期若留兼容分支,补兼容断言)。

**验收**:strict-verify §7 taskType 白名单仍过;e2e(`AtomicTaskPipelineE2eIT` 等)全链 SUCCESS;`resolveDispatchTopic("ATOMIC")` 命中 `.atomic`;FE jobType 下拉显示"原子任务"。

---

## 执行顺序(下午)

1. **先 Group A**(纯 rename)→ 全部通过 → 合并。blast radius 大但机械,先把代码层清干净。
2. **再 Group B**,按 B1→B2→B3→B4,**走迁移/双轨**,确认本地 + e2e 后再考虑环境切换。
3. Group A、B 各独立 PR;commit 前 `mvn clean`(stale cache 会漏报真错)。

## 回滚
- Group A:纯代码,`git revert` 即可。
- Group B:topic 双订阅期内可回滚 producer 到 `.spi`;job_type 迁移在"移除 'SPI'"那步之前都可逆(白名单仍含 SPI)。**先别急着出"移除 SPI"的第二步迁移**,留一个 release 的观察窗口。

## 待确认
- [ ] 当前各环境(尤其有没有 prod/staging)是否有 live `job_type='SPI'` 数据 / 在途 `.spi` 消息 → 决定 B3 走"双订阅"还是"硬切"。
