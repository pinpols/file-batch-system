# SDK / Orchestrator 协议 Dual-Rollout 指南

> 决策依据:[`docs/plans/sdk-roadmap-2026-h2.md`](../plans/sdk-roadmap-2026-h2.md) §1 决策记录 #3(2 周兼容窗口)
> + §15.5 PR 合并节奏(协议先,使用方后)。
> 红线机制:[`scripts/local/pre-push-sdk-checks.sh`](../../scripts/local/pre-push-sdk-checks.sh)
> + [`SdkWireContractTest`](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/controller/SdkWireContractTest.java)
> + [`docs/api/orchestrator-internal.openapi.yaml`](../api/orchestrator-internal.openapi.yaml)。

## 为什么需要 dual-rollout

租户**自托管 SDK**(ADR-035)运行在租户自己的 K8s / VM 上,平台无法**强制升级**。
平台先升 → SDK 后升的滚动期可能持续数天到数周;期间:

- 老 SDK 仍在跑,期望旧字段
- 平台已发新字段,老 SDK 必须能 ignore 而不 crash
- 新 SDK 升级后才能用新字段

任何**同 PR 同时改协议端 + 使用端**的做法会破坏这个不变量 —— 回滚困难 + review 难。

## 三步纪律(每次协议变更)

### Step 1 — 平台先发(orchestrator / batch-common DTO)

```
T0: Agent-ORCH 改平台 DTO 加新字段(可选 / null-safe) → 单独 PR
   - 字段标 nullable,有默认值
   - SdkWireContractTest 同步加新字段断言
   - docs/api/orchestrator-internal.openapi.yaml 同步加新字段
T0+1d: PR merged → 部署 orchestrator RC
```

**关键不变量**:此时老 SDK 收到带新字段的响应应该**ignore 而不 crash**。
SDK wire DTOs 全部带 `@JsonIgnoreProperties(ignoreUnknown = true)` 保证。

### Step 2 — 观察窗口(2 周;dev 阶段可跳过)

```
T0+1d → T0+15d: 监控
   - 老 SDK 心跳 / 报告 / 认领是否仍正常(error rate 不升)
   - 平台新字段写入是否符合预期
   - 老 SDK 日志无 "unknown field" / parsing exception
```

**dev 阶段跳过**:本 plan §15.9.B 明确开发阶段不真等 2 周,在 PR 描述里标
`dev-skip-dual-rollout, observe 2w post-deploy` 即可。**生产部署窗口必须真等。**

### Step 3 — SDK 跟进

```
T1: Agent-SDK 用新字段 → 单独 PR
   - 新增 wire DTO 字段或扩展现有 record
   - 升 sdk minor 版本(SemVer)
   - SdkWireContractTest 测新字段往返对齐
T1+1d: PR merged → 发布 SDK 新版,告知租户升级
T2: (可选) Agent-API / FE 跟上(如新字段需要在 console 展示)
```

## 字段变更分类决策

| 变更类型 | 是否要 dual-rollout | 处理 |
|---|---|---|
| **新增可选字段** | ✅ 需要 | 三步走;新字段必须可选 + null-safe |
| **新增必填字段** | ❌ 禁止 | 直接破坏老 SDK;改成"新增可选 + 平台默认回退" |
| **重命名字段** | ❌ 红线 | CLAUDE.md「禁重命名任何字段」;只能"加新字段 + 老字段过渡期保留"分两次发 |
| **删除字段** | ✅ 需要 + 30d 缓冲 | 1. 标 deprecated;2. 监控老 SDK 仍在用 → 通知租户升级;3. 30d 后真删 |
| **改字段语义**(类型 / 值域 / 必填) | ❌ 禁止 | 等价"破坏性变更",同删除路径走 |
| **新增 endpoint** | ✅ 仅平台侧 PR | SDK 不调用 = 无 dual-rollout 风险 |
| **删除 endpoint** | ✅ 需要 + 90d 缓冲 | 老 SDK 完全离线后才能删 |
| **schemaVersion 升级**(v1 → v2) | ✅ 协议主版本演进 | Kafka payload 走 SDK `TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS` 白名单;SDK 升级后才能加新 major |

## 红线 / 守门

1. **`SdkWireContractTest`(batch-orchestrator)**
   - SDK wire DTO record 改字段名/删字段 → 反序列化为平台 DTO 时关键字段断言 fail
   - 平台 DTO 改字段名 → 同样 fail
   - 守门强度:CI 红 + 阻塞 merge

2. **`docs/api/orchestrator-internal.openapi.yaml`**
   - 任何 `/internal/*` controller 变更必须同 PR 更新 yaml
   - pr-gate CI 会检测漂移(controller 改了 yaml 没改 → fail)

3. **`scripts/local/pre-push-sdk-checks.sh`**
   - push 前自动跑;Java 编码反例 + Flyway 版本号 + clean compile 三件套
   - 跳过用 `SKIP_SDK_CHECKS=1`(**严禁日常使用**)

4. **SDK 端 `TaskDispatchMessage.isSchemaSupported()`**
   - Kafka 派单消息收到 schemaVersion=v3+ → KafkaTaskConsumer reject + log WARN
   - 老 SDK 不会误处理新 major 消息

## 异常场景

### 紧急回滚(协议改坏了)

1. **默认 revert**(决策 #隐-2):平台 PR revert,redeploy
2. **不允许 hotfix 兼容补丁**(留 1 周观察期才允许)
3. SDK 端已经发的新版不动(向前兼容,新字段当 null 处理)

### 老 SDK 拒认新字段 / parsing exception

1. 查 PR 是否破纪律:重命名 / 删字段?→ revert
2. 否则查 SDK 是否漏了 `@JsonIgnoreProperties(ignoreUnknown = true)`(应是 record 默认)

### 老 SDK 调已删 endpoint → 404

1. 监控 `/internal/*` 404 rate
2. 联系租户升级 SDK
3. 90d 缓冲期内不真删 endpoint

## 参考

- [SDK roadmap H2-2026 plan](../plans/sdk-roadmap-2026-h2.md) §1 决策 #3 / §2 Phase 0 / §15.5
- [ADR-035 租户自托管 SDK](../adr/ADR-035-tenant-self-hosted-sdk.md) §9 两套绑定契约
- [Orchestrator internal OpenAPI](../api/orchestrator-internal.openapi.yaml)
- [SdkWireContractTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/controller/SdkWireContractTest.java)
- [SDK TaskDispatchMessage schemaVersion 实现](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatchMessage.java)
