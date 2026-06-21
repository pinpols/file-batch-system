# CI 激进提速 — C 方案(2026-06-02)

> 状态:已上线 PR `feature/ci-speedup-c-plan-pr-unit-only`
> 背景:PR-gate wall-clock 由最慢的 IT shard 决定(~15 min),日常 6+ 个 PR 排队反馈
> 慢。C 方案把 IT/E2E 全部从 PR-gate 拆走,PR 阶段只做"5 min 内的快速反馈",IT 由
> main push 的 full-ci-gate(post-merge)+ nightly staging-gate 回退。

## 改动概览(4 件)

### 1. PR-gate 改"只跑 unit,不跑 IT"

`.github/workflows/pr-gate.yml`:

- 3 个 maven shard job(`unit-it-a` / `unit-it-b1` / `unit-it-b2`)从 `mvn verify` 改成
  `mvn test -DskipITs=true`,只跑 surefire unit,跳过 failsafe IT。
- `cleanup orphan testcontainers` 步骤删除(不再起 Testcontainers)。
- `e2e-smoke` job 整个移除(原 R3-2 #262 引入),E2E smoke/critical/regression 全部
  交给 staging-gate(nightly,4 shard 并发)+ full-ci-gate(main push 后回退)。
- **job 名 `unit-it-a` / `unit-it-b1` / `unit-it-b2` 保留**(ruleset required check 配置
  在这 3 个名字上,改名会让 ruleset 失配)。
- 其它 job(`static-checks` / `security`)不动。

### 2. 文档 PR 跳所有 unit job

新增 `changes` job 用 `dorny/paths-filter@v3` 判定 docs-only:

- **触发 code path**(任一命中 => 跑 unit):`batch-*/**` / `batch-worker-sdk-python/**` /
  `db/migration/**` / `scripts/**` / `pom.xml` / `**/pom.xml` /
  `.github/workflows/**` / `.github/actions/**`。
- **不触发**(纯文档 / 示例 / 配置):`docs/**` / `*.md` /
  `examples/sample-tenant-worker*/**` / `examples/batch-worker-sdk-template/**` /
  `examples/sftp-push-executor/**` / `.github/dependabot.yml` / `renovate.json`
  等顶层非代码文件。
- 3 个 unit job 加 `if: needs.changes.outputs.docs-only != 'true'`;skipped 在 GitHub
  side 算 SUCCESS,ruleset required check 不挂。
- merge_group / push 事件 paths-filter 无 PR base,默认判 changed=true(安全回退,
  必跑 unit)。

### 3. Maven `.m2` cache

无需新增 — 所有 maven workflow 都通过 `.github/actions/setup-build-env` 复用
`actions/setup-java@v4` 的 `cache: maven`(等价 `actions/cache`)。`sdk-contract-parity.yml`
直接用 setup-java 也已配 `cache: maven`。本项默认已生效,本 PR 不动。

### 4. GitHub Merge Queue 入口

`pr-gate.yml` 的 `on:` 加 `merge_group: { types: [checks_requested] }`,允许后续在
仓库 UI 启用 Merge Queue 把 main 合并串行化、消除"PR 通过 → main 被破"风险。
其它 workflow(full-ci-gate / staging-gate / strict-verify)不动 — 它们由 push /
schedule 触发,不参与 merge queue 入栈检查。

## 预期效果

| 场景 | 改前 | 改后 | 收益 |
|---|---|---|---|
| 代码 PR wall-clock | ~15 min(IT shard 决定) | ~4-5 min(unit shard 决定) | **-66%** |
| 文档 / 示例 PR | ~15 min(同代码 PR) | ~1 min(只跑 static + security) | **-93%** |
| Self-hosted runner 用量 | 3 × 25 min × N PR + e2e-smoke 20 min | 3 × 8 min × N PR | **-65%** |
| 反馈到第一次失败信号 | ~6-8 min(surefire fail) | ~3-4 min(surefire fail) | **-50%** |

(数字是"过去 4 周 main reflog 上代码 PR 的中位数",非保证。)

## 风险与责任

**核心代价:PR 不跑 IT → main 偶尔会被 full-ci-gate 拦。**

应对:

1. **该 PR 作者负责立即 revert + 修复**。full-ci-gate 失败邮件 / Slack 通知到 PR
   作者,作者 1 小时内 revert 该 commit,然后在本地复现 IT 修好再 PR。
2. main 被破的 SLA:无人 merge 上去之前要 revert(< 1 h)。
3. **持续监控指标**:`main reflog` 上每周 full-ci-gate 失败次数。指标见
   `docs/runbook/ci-speedup-2026-06-02-metrics.md`(若 >1/周 持续 2 周 => 收回 C 方案,
   见下"何时回滚")。
4. **本地回退**:开发者在 PR 前可以本地手动跑 `mvn verify -pl <module> -am`
   或 `bash scripts/local/strict-verify.sh`(R3-3 已上线)做 IT。约束在 reviewer
   checklist。

## 回滚步骤

```bash
# 单步 revert 本 PR
git revert <merge-commit-sha>
git push origin main
```

或手工把 `.github/workflows/pr-gate.yml` 改回:

1. 删除 `changes` job。
2. 3 个 unit job:`mvn test -DskipITs=true` → `mvn verify -DskipITs=false`,
   `if:` 删掉 `needs.changes.outputs.docs-only != 'true'`,删 `needs: changes`。
3. 恢复 `cleanup orphan testcontainers` 步骤(从 git history 拷贝)。
4. 恢复 `e2e-smoke` job(从 git history 拷贝)。
5. 删除 `merge_group` 触发(可选,merge queue 不启用就不影响)。

## Merge Queue 启用指引(可选,后续)

启用 Merge Queue 后,合并通过 queue 串行化:每 PR 进 queue 后,GitHub 拿 main 最
新 commit + PR 重新 rebase 跑一次 required check,通过才合 main。可消除"PR 时通过、
合 main 时被并发 PR 破坏"的窗口。

操作步骤:

1. GitHub 仓库 → Settings → Rules → Rulesets → 编辑 main 的 ruleset。
2. 找到 "Require merge queue" 选项,勾上。
3. 配置:
   - Merge method: **Squash**(对齐当前 squash-only 策略)。
   - Build concurrency: 5(默认)。
   - Min/Max group size: 1 / 5。
   - Max wait time: 5 min。
   - Status checks 沿用现有 5 个 required check(`static-checks` / `unit-it-a` /
     `unit-it-b1` / `unit-it-b2` / `security`)。
4. PR-gate workflow 已经支持 `merge_group` 触发(本 PR 已加)。
5. 启用后,旧的 `gh pr merge --auto --squash` 自动语义不变,只是排队执行。

参考:[GitHub Docs — Merging a pull request with a merge queue](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/incorporating-changes-from-a-pull-request/merging-a-pull-request-with-a-merge-queue)

## 何时收回这些改动

触发条件(任一):

- **main 被破频率 > 1 次/周**,持续 2 周。原因:PR 阶段不跑 IT 的代价超过收益。
- **revert SLA 持续不达标**(超过 50% 的 main breakage 修复时间 > 1 h)。
- **开发者反馈本地 IT 跑通率 < 50%**(意味着 IT 漂移没人本地复现,只有 CI 暴露)。

收回方式:执行上面"回滚步骤"。

## 关联

- ruleset `strict_required_status_checks_policy=false` 已通过 GitHub API 关掉(允许
  required check 未跑完即可合 — 配合 paths-filter 跳 unit 的 docs PR)。
- R3-2 #262 引入的 e2e-smoke 在 staging-gate.yml 已有等价覆盖,本次移除不丢测试矩阵。
- R3-3 #264 引入的本地 `strict-verify.sh` 是开发者 PR 前的回退入口。
