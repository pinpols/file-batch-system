# ADR-029 · 共享配置基线独立模块 `batch-config-defaults`

- **状态**：Accepted
- **决策日期**：2026-05-14
- **关联**：架构 truth 第10轮（配置 drift）；ADR-001（持久层）

## 背景

`batch-defaults.yml` 是所有服务模块共享的配置基线（datasource / kafka / redis / actuator /
batch.* 共享键），最初放在 `batch-common/src/main/resources/`。各模块 `application.yml`
通过 `spring.config.import: "classpath:batch-defaults.yml"` 引入；模块自身只 overlay
专属配置（端口、application.name、hikari pool size 等）。

实践中暴露两个问题：

1. **边界不清**：`batch-common` 是业务公共代码模块（含 PgSessionSupport / Texts / DictEnum 等），
   把"配置基线"和"运行时业务工具"混在一起，导致：
   - 仅想改配置的人需要 reactor 编译整个 batch-common
   - 新成员看 `batch-common` 时不知道 yml 也归这个模块管
2. **缺 drift 守护**：如果有人在某服务模块的 `application.yml` 里悄悄写 `spring.kafka.bootstrap-servers`
   覆盖共享值，Spring 不会报错；下一次集群迁移时基线改了，那个模块仍走旧值，排查耗时。

## 决策

新建 maven 模块 **`batch-config-defaults`**，只含两类内容：

```
batch-config-defaults/
├── pom.xml
├── src/main/resources/batch-defaults.yml   <-- 从 batch-common 搬来
└── src/main/java/com/example/batch/config/defaults/
    └── BatchConfigDefaultsAutoConfiguration.java   <-- 空 marker
```

- **依赖关系**：`batch-common` 显式 `depends on` 新模块；下游所有模块通过 batch-common
  传递引入。这样：
  - reactor 顺序：`batch-config-defaults → batch-common → 业务模块`
  - 各服务 `application.yml` 的 `spring.config.import: "classpath:batch-defaults.yml"`
    无需改动（classpath 上一定有）
- **Marker 类不参与自动装配**：不放进 `META-INF/spring.factories` 或
  `AutoConfiguration.imports`，避免任何隐式 bean 副作用。它存在只为让 IDE/审计工具
  能从 Java 代码定位到这个模块。

### Guardrail：`ConfigDriftGuardTest`

`batch-common` 测试集新增 `ConfigDriftGuardTest`，断言：

1. `batch-defaults.yml` 只能存在于新模块路径，旧路径必须不存在（防回退）
2. 各服务模块 `application.yml` 不得复刻 `OWNED_KEYS` 集合中的键
   （`spring.kafka.bootstrap-servers` / `server.shutdown` / `batch.timezone.default-zone` 等）

`OWNED_KEYS` 维护原则：只放"基线就是唯一答案"的键。允许 overlay 的键（如各 worker
模块的 `spring.datasource.hikari.maximum-pool-size`，本身就是 module-specific）不进
此集合。

## 后果

### 好处

- 模块职责更清：`batch-common = 共享代码`、`batch-config-defaults = 共享配置`
- 改基线的 PR diff 干净（只动 `batch-config-defaults/`）
- Drift 守护：拼写 typo / 误覆盖会在 CI 阶段失败
- 未来添加 helm overlay / 多 profile 时多一个挂载点（`application-prod.yml` 等也可
  搬到新模块或与 yml 同处）

### 代价

- 多一个 maven 模块（构建时间增加 < 1s，可忽略）
- 跨仓库提交 yml 改动需要同时改 ADR-029 OWNED_KEYS（属于"故意的摩擦"，符合预期）

## 演进

- 后续如出现"按 profile 分基线"（`batch-defaults-prod.yml` / `batch-defaults-staging.yml`），
  也放在本模块，由各服务通过 `spring.config.activate.on-profile` 引入
- 不计划暴露 `@ConfigurationProperties` 类：基线值散布在各模块自己的 properties bean，
  本模块只承担"yml 物理载体 + 防 drift"两个职责

## 替代方案（已驳回）

- **保留原状（不抽模块，只加 guardrail）**：能挡 drift，但边界依然混乱，新人需要看
  CLAUDE.md 才知道 yml 归 batch-common —— 治标不治本。
- **每个模块各放自己的 defaults**：完全失去"基线"概念，回到 drift 起点。
