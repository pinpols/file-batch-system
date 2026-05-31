# SDK ↔ Orchestrator 协议 Dual-rollout 指南

> 适用范围:任何同时影响 `batch-orchestrator` `/internal/*` controller 和 `batch-worker-sdk` 的协议字段变更。
>
> 出处:[`docs/plans/sdk-roadmap-2026-h2.md`](../plans/sdk-roadmap-2026-h2.md) §2 Phase 0 / §4.2 / §15.5。
>
> 配套:[`docs/api/orchestrator-internal.openapi.yaml`](../api/orchestrator-internal.openapi.yaml) — yaml 是契约权威源。

## 1. 为什么不能一锅端

SDK 跟 orchestrator 是**跨发布周期**部署:

| 角色 | 升级窗口 | 重启代价 |
|---|---|---|
| orchestrator | 受运维窗口控制(通常周末) | 中,需 drain in-flight |
| SDK(租户自托管) | **租户自己定**,可能几周不升 | 高,可能造成业务断流 |

如果一次 PR 同时改协议两端,会出现两类失败:

- **平台先部署 → 老 SDK 收到新字段** → JSON 反序列化 unknown property 报错(若没 `@JsonIgnoreProperties(ignoreUnknown=true)`)
- **SDK 先部署 → 老平台收到新字段** → 平台 reject 或字段被忽略,SDK 误以为生效

## 2. Dual-rollout 三步走

```
T0:  Agent-ORCH 改 DTO 加新字段(可选 / null-safe)→ 单独 PR(只动 orchestrator)
     合 main + 部署 RC
T0 + 2w(开发期可标 "dev-skip"):观察 1 个发布周期 / 真实部署 2 周
T1:  Agent-SDK 用新字段 → 单独 PR(只动 SDK)
     合 main + 发布 SDK 新版
T2:  Agent-API / FE 跟上(如需) → 单独 PR
```

**禁**:同一 PR 同时改协议端 + 使用端(回滚困难 + review 难)。

## 3. 协议字段四种变更场景

### 3.1 新增可选字段(✅ 最常见,直接做)

平台 DTO 加新字段 `Foo bar`(`null` 兼容),SDK 反序列化 ignore unknown。

- 平台 PR:加字段 + 写入逻辑(读时 null-safe)
- SDK PR(下一发布周期):读取该字段
- yaml:`orchestrator-internal.openapi.yaml` 加 schema 字段
- 测试:`SdkWireContractTest` 自动覆盖(字段集对齐)

**例**:Phase 2 `TaskDispatchMessage.schedulingContext`、Phase 3 `WorkerHeartbeatDto.descriptors`。

### 3.2 新增必填字段(⚠️ 高风险,先可选 → 后转必填)

- T0 PR:平台 + SDK 都改成"可选"(`@Nullable`,缺失走默认值)
- T0+2w:观察 100% 流量带新字段
- T1 PR:平台端转"必填"(reject 缺字段),SDK 同步去掉 null fallback

**例**:Phase 0 `TaskDispatchMessage.schemaVersion` — 当前 SDK 缺失 → `"v1"`;后续 Phase 演进到 `"v2"` 时切必填。

### 3.3 重命名字段(❌ 红线,不允许)

CLAUDE.md 明文红线:**禁重命名任何字段**(破坏 mybatis xml / canonical constructor / SDK 客户)。

需要语义变化 → 新字段 + 老字段保留至少 30 天 → 老字段 deprecate 注释 → 跨 2 个版本后 drop。

### 3.4 字段类型变更(⚠️ 等同重命名,走 3.3)

`Long taskId` → `String taskId` 这种 JSON 兼容但语义不兼容的改动,SDK 反序列化期会 silent fail。强制走 3.3 的"新字段并存"路径。

## 4. PR 描述模板

跨协议 PR 描述里必须答出以下三问:

```markdown
## Dual-rollout 自查
- [ ] 本 PR 只改协议**端**之一(orchestrator 或 SDK),不混改
- [ ] 新增字段是可选还是必填?如必填,先发可选 PR 观察 2w 再转必填
- [ ] `docs/api/orchestrator-internal.openapi.yaml` 已同步
- [ ] `SdkWireContractTest`(batch-orchestrator)已加 / 已绿
- [ ] 字段未重命名(违反 CLAUDE.md 红线)
- [ ] 老 SDK 收到本变更后能正常工作(向后兼容验证)
```

## 5. 验证清单

### 平台 PR 自查

```bash
# 1. 契约测试绿
mvn -pl batch-orchestrator test -Dtest=SdkWireContractTest

# 2. yaml 同步
grep -q "<新字段名>" docs/api/orchestrator-internal.openapi.yaml || echo "MISS yaml"

# 3. 老 SDK jar 跑新平台(本地)
docker run --rm -e BATCH_BASE_URL=http://platform-rc \
  ghcr.io/example/batch-worker-sdk:LAST_RELEASE 5m-smoke
```

### SDK PR 自查

```bash
# 1. SDK 自身测试绿
mvn -pl batch-worker-sdk test

# 2. SDK 收到 LAST_PRODUCTION 平台的 JSON 不 break
mvn -pl batch-worker-sdk test -Dtest=*BackwardCompatTest

# 3. 平台已 deploy 新字段(查 RC 日志 / curl /actuator/info)
curl http://platform-rc/actuator/info | jq '.build.commit'
```

## 6. 开发期(dev-skip)与生产期的差别

| 阶段 | 是否真等 2 周 |
|---|---|
| Plan §15.9 单对话连续模式 / phase 内开发 | ❌ 不等,PR 描述标 `dev-skip-dual-rollout, observe 2w post-deploy` |
| 生产 RC 部署后 | ✅ 真等 2 周,观察老 SDK 流量无 break |
| Hotfix 安全修复 | ⚠️ 可压缩到 3 天,但必须 RC 灰度先全 100% 平台后才发 SDK |

## 7. 回滚策略

| 场景 | 动作 |
|---|---|
| 平台部署后老 SDK 大面积报错 | **revert 平台 PR**(默认策略,Plan §1 隐-2) |
| SDK 发布后租户报错 | 撤回 SDK release tag + 通知租户回滚 |
| 协议字段需紧急废弃 | 平台 + SDK 同时部署 hotfix 关掉读写(留 1 周观察期才发) |

## 8. Phase 0 落地样例

Phase 0 PR(本指南配套)动作:

1. SDK `TaskDispatchMessage` 加 `schemaVersion`(向后兼容,缺失 → `"v1"`)
2. SDK 新增 5 个 wire DTO(`sdk/wire/`),代码内**暂未启用**(还没改 `PlatformHttpClient` 用它们)
3. 平台不动 controller / DTO 字段,只加 `SdkWireContractTest` 作为契约守护
4. 新建本文件 + `orchestrator-internal.openapi.yaml`

→ Phase 1 SDK PR 才会真正切 wire DTO(替换 `Map<String, Object>`),那时本指南的 dual-rollout 完整流程才被踩通一次。

## 9. 参考

- [SDK 路线图 2026 H2](../plans/sdk-roadmap-2026-h2.md)
- [orchestrator-internal OpenAPI](../api/orchestrator-internal.openapi.yaml)
- [ADR-035 租户自托管 SDK](../architecture/adr/ADR-035-tenant-self-hosted-sdk.md)
- [CLAUDE.md API 文档同步](../../CLAUDE.md)(根)
