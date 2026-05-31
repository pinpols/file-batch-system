# SDK Roadmap H2-2026 — 进度记录

> 日期倒序。每个 phase 收官后追加 5 行 retro:实际 vs plan diff + 经验。
> 维护规则参见主 plan §1 决策 #隐-1。

---

## 2026-05-31 — ⚠️ Phase 0 代码完成,**push 被环境权限拦截**

- **状态**:本地分支 `feature/sdk-phase-0-protocol-foundation` 已 commit 1 个 commit;`git push` 与 `mcp__github__push_files` / `create_branch` 均返回 `403 Permission to pinpols/file-batch-system.git denied to idengzhao`。GitHub MCP 鉴权身份是 `idengzhao`,但目标仓库 `pinpols/file-batch-system` 上没有 write 权限,无法走 routine 自动 push + create PR 流程。
- **退出原因**(对照 supervisor routine「停下条件」):**触红线(pre-push 拒绝)**;需人工介入授权或改鉴权身份。
- **本地交付**:见下方 Phase 0 完成段;`mvn -pl batch-worker-sdk test` 140/140 绿;`SdkWireContractTest` 9/9 绿;pre-push self-check 全过。
- **下次启动**:用户授权完成后,本地 `git push -u origin feature/sdk-phase-0-protocol-foundation` 即可,后续 PR 描述 + auto-merge / plan doc 划线已就绪(commit 已含,分支 ready)。

---

## 2026-05-31 — Phase 0 完成(PR #SDK-P0-1,待 push)

- **范围**:0.1 schemaVersion + reject 未知 major、0.2 5 个 SDK wire DTO records (`sdk/wire/`)、0.3 `SdkWireContractTest`(batch-orchestrator,9 测试)、0.4 dual-rollout 指南 + 新建 `docs/api/orchestrator-internal.openapi.yaml`。
- **实际 vs plan**:工作量 8h(plan 估 8h)持平。比 plan 多做了:`TaskDispatchMessage` 加 7 参兼容构造避免 break 8 个旧测试 + 拉宽 `resolvedMajor` 支持 `vN.M` / `vN-suffix` 两种后缀。比 plan 少做的:**未** refactor `PlatformHttpClient` / scheduler 改用新 records(仍走 `Map<String, Object>`)—— 拆到 Phase 1 顺手做,本 PR 保持 ≤ 600 LoC 净增。
- **环境坑**:本地 JDK 21,但项目要 JDK 25(`.mvn/jvm.config` 用 `--sun-misc-unsafe-memory-access=allow`)→ 临时下载 Oracle JDK 25 + 拷 JDK 21 cacerts 解决 SSL 信任。后续 routine 启动需复用 `/opt/jdk25`。
- **测试结果**:`mvn -pl batch-worker-sdk test` 140/140 绿(原 135 + 新 5);`SdkWireContractTest` 9 测试绿;**未跑** orchestrator 全模块 verify(memory: `feedback_p1a_no_full_test`)。
- **后续**:Phase 1 启动条件已具备(#SDK-P0-1 必须 merge)。Phase 2 协议层在 #SDK-P0-1 + Phase 1 全部 merge 后再开。
