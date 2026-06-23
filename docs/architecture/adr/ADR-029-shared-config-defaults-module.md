# ADR-029 · 共享配置基线 `batch-defaults.yml`

- **状态**:**Superseded(原决策)** → **Revised: Accepted(2026-05-16 修订)**
- **原决策日期**:2026-05-14
- **修订日期**:2026-05-16
- **关联**:架构 truth 第10轮(配置 drift);ADR-001(持久层)

---

## 🔁 2026-05-16 修订(当前生效决策)

### 修订摘要

**取消独立模块 `batch-config-defaults`,把 `batch-defaults.yml` 搬回 `batch-common/src/main/resources/`。** 原 drift 守护测试 `ConfigDriftGuardTest` 继续生效,并新增 classpath 存在性断言作为"模块缺失"的回退。

### 修订理由

1. **业界主流不为单个 yml 单独建模块**。常见做法是:
   - 放到 base/common 模块(本项目规模)
   - 走 ConfigMap / Helm values(K8s 部署)
   - 走配置中心(Apollo / Nacos / Spring Cloud Config,大厂规模)

   "独立 Maven 模块只装一个 yml + 空 marker 类"在主流 Java 微服务生态里几乎不见,**搜索任何 Spring Boot / Spring Cloud 项目都不会推荐这种结构**。新人看到 `batch-config-defaults/` 目录(只含 1 个 yml + 1 个空类)会困惑"为什么单建一个模块"。

2. **原版"边界不清"的痛点用测试守护足够解决**,不需要模块边界。`ConfigDriftGuardTest` 已经能拦截:
   - 服务模块 application.yml 复刻 OWNED_KEYS(漂移)
   - batch-defaults.yml 从 classpath 消失(被 refactor 移除)

   测试守护一次执行 0.x 秒,比"多建一个模块 + 9 处 pom dep 声明 + reactor 多构建一节点"的运维负担小一个量级。

3. **"独立模块=显式依赖=编译期失败"的论点真实但收益小**。所有 9 个服务模块都依赖 `batch-common`(载有大量公共代码,不可能哪天被 refactor 移除);把 yml 放在 batch-common 里,被静默移除的实际概率接近零。守护测试在 1 秒内 fail-fast,等价于"运行时 fail-fast",并不显著差于"编译期 fail-fast"。

4. **PR diff 干净度不是模块边界的理由**。仅改 yml 时,`git diff batch-common/src/main/resources/batch-defaults.yml` 同样干净;搜 PR 文件名也能立刻看到改的是 yml。

### 物理结构(修订后)

```
batch-common/
├── src/main/resources/batch-defaults.yml      ← 共享基线(主流位置)
├── src/main/resources/application.yml         ← batch-common 自己用(测试启动等)
├── src/main/resources/META-INF/...            ← AutoConfiguration imports
└── src/test/java/.../ConfigDriftGuardTest.java ← drift + 存在性守护
```

各服务模块的 `application.yml` 继续写 `spring.config.import: "classpath:batch-defaults.yml"`,无需改动。

### `ConfigDriftGuardTest` 守护范围(2 件事)

1. **存在性**:
   - `batch-defaults.yml` 必须位于 `batch-common/src/main/resources/`
   - 测试 classpath 上必须能 `getResource("batch-defaults.yml")` 返回非 null
   - 任一不满足 → 测试失败,等价于"模块缺失 fail-fast"
2. **Drift**:服务模块的 `application.yml` / `application-<profile>.yml` 不得复刻
   `OWNED_KEYS`(`spring.kafka.bootstrap-servers` / `server.shutdown` /
   `batch.timezone.default-zone` 等基线"唯一来源"键);白名单维护原则不变。

### 修订执行清单(2026-05-16 commit)

- 删除 `batch-config-defaults/` 整个目录(pom / marker 类 / yml 全删)
- 从根 `pom.xml` `<modules>` 移除 `<module>batch-config-defaults</module>`
- 9 个服务模块 pom 移除对 `batch-config-defaults` 的显式依赖 + 相关注释
- `batch-defaults.yml` 物理移动到 `batch-common/src/main/resources/`
- `ConfigDriftGuardTest` 增 `baselineYamlExistsAtCanonicalLocation` +
  `baselineYamlIsReachableFromClasspath` 两个测试方法,改 repoRoot 定位逻辑
- CLAUDE.md §模块边界 同步更新

### 兼容性影响

- **二进制兼容**:无(yml 是 resource 文件,不影响 API)
- **应用启动**:无变化(`classpath:batch-defaults.yml` 仍然 resolve 到同一文件)
- **CI / 干净 clone**:无操作即可工作
- **本地已 build 的工作树**:首次 `mvn clean install` 后正常;旧 `~/.m2/repository/io/github/pinpols/batch/batch-config-defaults/` 残留 jar 不影响后续构建,可手动删除

---

## 📜 历史(原决策,已被上方修订替代)

### 原背景(2026-05-14)

`batch-defaults.yml` 是所有服务模块共享的配置基线(datasource / kafka / redis / actuator / batch.* 共享键),最初放在 `batch-common/src/main/resources/`。各模块 `application.yml` 通过 `spring.config.import: "classpath:batch-defaults.yml"` 引入;模块自身只 overlay 专属配置(端口、application.name、hikari pool size 等)。

原决策识别的痛点:

1. **边界不清**:`batch-common` 是业务公共代码模块(含 PgSessionSupport / Texts / DictEnum 等),把"配置基线"和"运行时业务工具"混在一起。
2. **缺 drift 守护**:如果有人在某服务模块的 `application.yml` 里悄悄写 `spring.kafka.bootstrap-servers` 覆盖共享值,Spring 不会报错。

### 原决策(2026-05-14)

新建 maven 模块 **`batch-config-defaults`**,只含 `batch-defaults.yml` + 空 marker `@Configuration` 类。各服务模块显式 `dependency` 该模块,以求"编译期失败 > 启动期失败 > 运行时漂移"的安全梯度。

### 为什么修订(回顾)

原决策"用 Maven 模块边界来表达配置基线契约"是一个**合理但偏保守的小众选择**。在两条诉求间二选一:

| 维度 | 独立模块 | 留 batch-common + 测试守护 |
|---|---|---|
| 静默漂移防护 | 编译期 fail | 测试期 fail(<1s) |
| 模块结构 vs 业界 | 不常见 | 主流 |
| 新人理解成本 | 高(看到空模块困惑) | 低 |
| 维护成本 | 9 处 pom dep + 1 个模块 | 0 额外 |
| Drift 检测能力 | 同 | 同 |

修订选择后者:**主流位置 + 测试守护**收益等价,认知成本和维护负担显著更低。

### 已驳回的替代方案(原 ADR 列出,修订后说明)

- ~~**保留原状(不抽模块,只加 guardrail)**~~ → **被修订采纳为新主决策**。原 ADR 驳回的理由"治标不治本"在事后审视下站不住——guardrail 本身就足够覆盖原决策想防的所有漂移类型。
- ~~**每个模块各放自己的 defaults**~~ → 仍然驳回(回到 drift 起点)。

---

## 备注

本次修订是**对工程过度抽象的纠偏**示例。原决策不算错,但在小团队 / 单仓 / jar 部署场景下 ROI 倒挂。**未来若上 K8s,直接走 ConfigMap 注入 yml,不需要任何 Java 模块层抽象**。
