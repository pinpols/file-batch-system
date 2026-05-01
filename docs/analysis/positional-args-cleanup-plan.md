# 位置参数构造臃肿治理方案 · v4（main + test 全清版）

> **产出日期**：2026-05-01
> **状态**：已闭环
> **版本**：v1（49 处 nulls≥3）→ v2（198 处含 argc=4-6）→ v3（61 处 main，按 Effective Java / Google Style 收窄）→ **v4（main + test 全清，守护测试扩到 test 路径）**
> **关联规约**：CLAUDE.md §「方法参数约束」（本方案同步追加"调用方约束"子节）
> **触发**：CLAUDE.md "方法参数 ≥7 必须封装为 Param 类" 第一阶段落地后，参数臃肿从方法签名搬到了构造调用，留下 main 61 处 + test 41 处 `f(new XxxParam(a,...,h))` 反例（argc>6）

## 1. 目标

消除 main + test 中两类反例（业界标准 + Effective Java Item 1-2 对齐）：

1. **方法签名 argc>6** —— CLAUDE.md 现有规约硬性违反，必须封装为 Param/Command record
2. **inline `f(new Xxx(...))` Xxx 构造参数 >6** —— 加 `@Builder` + 提取引用变量 + builder 链（默认值不显式 set）

**不治理**：
- `argc≤6` 的 inline new（业界无依据，4-6 参数 inline new 是 Java 标准写法）
- 声明式注册类（`ConsoleMenuRegistry` / Excel `*SchemaRegistry` / Spring `@Configuration` 列表 / `List.of(new Foo(...))` 等）—— inline new 在声明式数据结构里是**业界鼓励**的可读写法

## 2. 范围

| 桶 | 阈值 | 数量 | 备注 |
|---|---|---:|---|
| **① 方法签名 argc>6（=7）** | 必修 | **7** | CLAUDE.md 硬性违反 |
| **② inline new argc>6** | 加 `@Builder` + 引用 | **54** | ~25 个治理类型 |
| **合计** | | **61 处** | + ① 封装后产生的 ~10 处新调用方 |

**~~原桶 ③ argc=4-6 共 137 处~~** —— 经业界标准评估删除（详见 §7 不做的）。

### 2.1 桶 ① 7 处方法签名（argc=7）

| 文件 | 方法 | 类型 | 处理 |
|---|---|---|---|
| `ConsoleApiKeyRepository.java:59` | `insert` | Spring Data JDBC `@Modifying @Query` | **豁免**（CLAUDE.md 框架契约豁免条款） |
| `ConsoleWebhookSubscriptionRepository.java:68` | `insert` | Spring Data JDBC `@Modifying @Query` | **豁免** |
| `ConsoleWebhookSubscriptionRepository.java:89` | `update` | Spring Data JDBC `@Modifying @Query` | **豁免** |
| `RetryScheduleMapper.java:31` | `markFailed` | MyBatis mapper（原生 `#{p.field}`） | ✅ `MarkFailedParam` record |
| `ConsoleSelfServiceJobService.java:72` | `submitApproval` | service 内部 private | ✅ `SubmitApprovalParam` record |
| `PlatformFileRuntimeRepository.java:329` | `finishStepRun` | worker 内部 private | ✅ `FinishStepRunParam` record |
| `ParseSupport.java:142` | `writeParsedRecord` | worker import 公共 | ✅ `ParsedRecordWriteParam` record |

桶 ① 实际治理 **4 处**（service / worker / MyBatis），3 处 Spring Data JDBC `@Query` 接口走豁免（业界标准）。

### 2.2 桶 ② 54 处 inline argc>6（按类型聚合）

| 类型 | 调用次数 | 主要文件 |
|---|---:|---|
| `ApprovalSubmitContext` | 6 | `DefaultConsoleJobRecoveryService` ×4 + `DefaultConsoleFileApplicationService` + `DefaultConsoleJobApprovalService` |
| `FileExecContext` | 4 | `DefaultConsoleFileApplicationService` |
| `BadRecordContext` | 3 | `ImportRecordGovernanceService` |
| `DispatchHealthUpsertCommand` | 3 | `DispatchChannelHealthService`（success/failureBump/recalcBackoff） |
| `PipelineStepTemplate` | 3 | worker import/export/process 三个 stage executor |
| `NodeRunOutcome` | 3 | `DefaultTaskOutcomeService` ×2 + `TaskOutcomeService` |
| `BatchDayAuditLogParam` | 2 | `LaunchBatchDayService` |
| `WebhookDeliveryLogInsertParam` | 2 | `WebhookDispatcher` |
| `ArchivePolicyUpsertParam` | 2 | `ConsoleArchivePolicyService` + `ConsoleArchivePolicyController` |
| `SecurityOptionsInput` | 2 | `DefaultConsoleFileTemplateApplicationService` |
| `TaskOutcomeCommand` | 2 | `DefaultTaskOutcomeService` + `TaskControllerApplicationService` |
| `ColumnResolutionSpec` | 2 | `AbstractExportFormat` |
| 散点 16 类各 1 处 | 16 | `WorkerRegistryRecord` / `BatchDayInstanceRecord` / `JobDefinitionRow` / `ChildLaunchContext` / `DagAdvanceContext` / `LaunchRequest` / `FileGovernanceCommand` / `ArrivalGroupGovernanceCommand` / `CompensationSubmitCommand` / `CreateSubscriptionCommand` / `UpdateSubscriptionCommand` / `DefinitionChangeContext` / `ConsoleRealtimeDomainEvent` / `ImportAuditContext` / `AlertEmitRequest` / `TriggerStatusInfo` / `SftpUploadContext` / `ExportFormatContext` / `Entry`（WorkerRegistryCache 内） / `PipelineStepDefinition` |

### 2.3 ~~桶 ③ argc=4-6~~（删除，不治理）

经业界标准评估（Effective Java、Google Style Guide、Oracle Java Conventions），argc=4-6 的 inline `f(new Xxx(...))` 是 Java 标准可读写法，**没有任何主流规约**禁止。原桶 ③ 137 处治理动作"仅提取引用"是奇怪的中间态（既不加 builder 又强制抽变量），业界没有先例。**删除**。

详见 §7 不做的。

## 3. 治理策略（统一）

### 3.1 桶 ①：方法签名 argc>6 封装

每处生成新 `<Method>Param` 或 `<Domain>Command` record + `@Builder`，方法签名收口为单参，所有调用方一并改 builder + 引用变量。

```
@Builder
public record InsertApiKeyParam(
    String tenantId, String keyId, String keyHash, String label,
    String createdBy, Instant createdAt, String description) {}

// 调用方
InsertApiKeyParam param = InsertApiKeyParam.builder()
    .tenantId(tenantId)
    .keyId(keyId)
    .keyHash(keyHash)
    .label(label)
    .createdBy(createdBy)
    .createdAt(createdAt)
    .description(description)
    .build();
repository.insert(param);
```

### 3.2 桶 ②：inline argc>6 加 `@Builder` + 提取引用 + builder 链

**统一动作**（无降级）：

1. **目标类加 `@Builder`** ——
   - record：直接加（零风险）
   - class：加 `@Builder` 同时**用注解兜底空参**，**不降级**（见 §3.4）
2. **调用方提取引用变量**：`Type t = Type.builder()....build(); f(t);`
3. **null / false / 0 字段不显式 set**，靠 Lombok 默认值（见 §3.5）

### 3.3 加 `@Builder` 时空参构造的注解兜底（关键决策）

按用户决策"使用注解解决空参问题，不要降级到只提取引用"。

| 类形态 | 加 builder 的注解组合 | 理由 |
|---|---|---|
| **record** | `@Builder` 单注解 | record 本来就没有也不能有空参构造，反射路径走 canonical constructor，无影响 |
| **class 已有 `@Data` / 显式 `public Foo() {}`** | `@Builder` 单注解 | 已有空参，Lombok 不会冲突 |
| **class 仅有隐式空参（无任何构造器）** | `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor` 三连 | Lombok 生成 `@AllArgsConstructor` 会消除隐式空参；`@NoArgsConstructor` 显式恢复；`@AllArgsConstructor` 给 builder 内部用 |
| **class 已有自定义构造（如带参单一构造）** | `@Builder` + `@Tolerate` 显式声明空参 | 比三连干净，避免误生成 setter；`@Tolerate` 让 Lombok 不视为构造器冲突 |

**铁律**：**不允许"加了 @Builder 后破坏隐式空参"** —— 必须用上表注解组合补齐。**不降级到桶 ③（仅提取引用）**。

`@Tolerate` 示例：

```
import lombok.experimental.Tolerate;

@Builder
public class XxxDto {
  String a;
  String b;

  @Tolerate
  public XxxDto() {}
}
```

### 3.4 null / false / 0 字段：不显式 set，依赖 Lombok 默认值

| 字段类型 | 原位置参数传值 | builder 是否 set |
|---|---|---|
| 引用类型 | `null` | ❌ 不 set |
| `boolean` | `false` | ❌ 不 set |
| `int` / `long` | `0` / `0L` | ❌ 不 set |
| 任意 | 非默认值 | ✅ `.field(value)` |

示例：

- 原：`new DispatchResult(false, null, null, false, false, "smtp_host missing", null)`（7 参数全填）
- 新：`DispatchResult.builder().message("smtp_host missing").build()`（仅 set 非默认值）

### 3.5 调用方铁律：禁止 inline build

任何方法实参里出现 `Type.builder()....build()` 或 `new Type(...)` 长构造，都必须先抽到上一行变量。

允许 `Type.builder()....build()` / `new Type(...)` 直接出现的位置仅 2 个：

1. `Type t = ....;` 赋值右侧
2. `return ....;` 单一 return（不嵌套）

### 3.6 字段顺序与 entity 红线

- ❌ **不动 record 字段顺序、不增删字段**（保护 mybatis xml `#{q.xxx}`、保护 canonical constructor 调用方）
- ❌ **不动 Spring Data JDBC entity / `@Entity` 持久化类**（PR 启动前先 grep `@Table` / `@Id` / `@Column`，命中即排除）
- ❌ **不重命名任何字段**

## 4. 提交策略：1 个大 PR

按用户决策"大 PR"——本方案 61 处 + ① 封装产生的 ~10 处新调用方 + 守护测试 + CLAUDE.md 规约更新 + changelog 追加，**全部一个 PR 合入**。

| 改动项 | 预估 diff |
|---|---:|
| ① 7 处方法封装 + 7 个新 Param record + 调用方 ~10 处 | ~300 行 |
| ② 54 处 inline argc>6 + ~25 类型加 `@Builder`（含 class 注解兜底） | ~600 行 |
| 守护测试 `PositionalArgsConventionTest` | ~120 行 |
| CLAUDE.md §方法参数约束 追加调用方子节 | ~30 行 |
| docs/changelog.md 追加规约变更条目 | ~5 行 |
| **合计** | **~1100 行 diff** |

**提交内 commit 拆分**（同 PR 多 commit 便于 review）：

1. `chore(convention): 封装方法签名 argc>6 = 7 处 + 新 Param records`（桶 ①）
2. `chore(convention): inline new argc>6 全清 = 54 处 + 25 类型加 @Builder`（桶 ②）
3. `test(convention): PositionalArgsConventionTest 守护拦回潮`
4. `docs(convention): CLAUDE.md §方法参数约束 加调用方子节 + changelog`

每个 commit 独立编译 + 测试通过，PR 合入前跑全模块 `mvn -DskipITs test`。

## 5. 风险点

| # | 风险 | 缓解 |
|---|---|---|
| R1 | Mybatis XML `#{q.xxx}` 字段引用 | 加 `@Builder` 不动字段名/顺序，零影响 |
| R2 | `@Builder` 在 class 上消除隐式空参 → 反射 break | §3.3 注解兜底（三连或 `@Tolerate`），**不降级** |
| R3 | Lombok 默认值与原位置参数等价性 | boolean=false / int=0 / long=0L / 引用=null，与 §3.4 表对齐 |
| R4 | 测试 mock `thenReturn(new DispatchResult(...))` | 一并扫 test 路径迁移到 builder/引用 |
| R5 | 桶 ① 封装后调用方未一并迁 | 7 处方法封装与调用方迁移在同一 commit，避免编译断点 |
| R6 | record 字段顺序被 IDE 自动排序破坏 | PR commit 前 `git diff` review 字段顺序未变 |
| R7 | Spring Data JDBC entity 误判 | 启动前 grep `@Table` / `@Id` / `@Column`，命中即排除 |

## 6. 守护测试

`PositionalArgsConventionTest`（升级自 `QueryRecordConstructionConventionTest`），**扫描 `main + test` 双路径**（v4：test 也走同一约束）：

| 规则 | 反例 | 拒绝模式 |
|---|---|---|
| 桶 ①/② 残留 raw 构造 | `f(new XxxXxx(a, b, c, d, e, f, g))` 出现在方法实参位置（argc≥6） | `\bnew\s+[A-Z]\w*\s*\(...\)` + 前缀回溯判定（前为 `(` / `,` 才算反例） |
| 桶 ② 残留 inline builder | `mapper.x(Type.builder()....build())` | `\.\w+\s*\(\s*\w+\.builder\(\)`（排除 `=` / `return` 前缀） |

**白名单**：本方案治理过的所有类型（约 50 个：Query record 17 类 + Param/Command 30+ 类 + Entry）入白名单，新治理加白名单条目。**只对白名单类型生效**，避免对 JDK / Map.Entry / 声明式注册类等误报。

**赋值/return 豁免**：`Type t = new Type(...)` / `return new Type(...)` 是允许位置（不在方法实参里）。

## 7. 不做的

- ❌ **argc=4-6 inline new** —— 业界无规约依据（Effective Java / Google Style / Oracle Conventions 都未禁止 `f(new Foo(a,b,c,d,e))`），原桶 ③ 137 处全部不治理
- ❌ **声明式注册类豁免** —— `ConsoleMenuRegistry`（41 处 MenuItem + 8 处 MenuGroup）/ Excel `*SchemaRegistry`（8 处 SheetDef）/ Spring `@Configuration` 列表 / `List.of(new Foo(...))` 等：inline new 在声明式数据结构里是业界鼓励的可读写法，**强制抽变量反而是反模式**
- ❌ 重排任何 record 字段
- ❌ 重命名任何字段
- ❌ Spring Data JDBC entity 强制 `@Builder`（侵入持久化路径）
- ❌ 顺手"清理"邻近无关代码
- ❌ 守护测试全仓扩（用白名单方式）

## 8. 验收

- [ ] 61 处全部清零（main grep `\b<methodName>\(.*\bnew\s+\w+\([^)]*,[^)]*,[^)]*,[^)]*,[^)]*,[^)]*,` → 0）
- [ ] `mvn -pl <全模块> -DskipITs test` 全绿
- [ ] `PositionalArgsConventionTest` 在 main + test 双路径通过
- [ ] CLAUDE.md §方法参数约束 子节落地
- [ ] docs/changelog.md 追加 2026-05-01 条目
- [ ] hardening-backlog.md `V6-P2-POSITIONAL-ARGS` 状态 `方案待批准` → `已闭环`

## 9. 与其他文档的关系

- `CLAUDE.md` §方法参数约束 末尾追加"调用方约束"子节（同 PR 落地）
- `docs/changelog.md` 追加 2026-05-01 规约变更条目
- `docs/analysis/hardening-backlog.md` `V6-P2-POSITIONAL-ARGS` 索引同步状态
- 完成后归档到 `archive/analysis/`
