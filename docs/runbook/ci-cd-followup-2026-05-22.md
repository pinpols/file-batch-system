# CI/CD 5 项落地遗留 / 待验证清单

> **⚠️ 2026-05-23 状态更新**:`promote-staging.yml`(下方多次引用)已删除;`OPS_REPO_TOKEN` 这条 followup 不再适用。涉及 `staging-gate` / `capacity-gate` 的 followup 同样作废(workflow 也删了)。详见 [ci.md](./ci.md) 顶部。
>
> 日期:2026-05-22
> 范围:CD roadmap(`docs/runbook/ci-cd-roadmap-2026-05-22.md` 已丢失,以本文档为准)5 项实施后的遗留项 + 跟进 owner。
> 主 commit:`9dd6c4ee`(TIA + GitOps)+ `a9a99501`(GHA cache + Dependabot)。

## 总览

| # | 项 | 状态 | 阻塞点 | Owner | 预计 |
|---|---|---|---|---|---|
| 1 | GHA cache | ⚠️ 待 CI 验 | 需下一次 CI run 才能看 cache hit 率 | 自动 | 下次 push |
| 2 | Branch protection | ⚠️ 配置在但不生效 | 私仓 + 免费账号,GitHub 强制 bypass | 待升 Team org / 转 public | 商业化时 |
| 3 | Dependabot | ⚠️ 待首次扫描 | 周一 03:00 自动扫;或手动触发 | 自动 | 本周一 |
| 4 | TIA POC | ⚠️ 影子模式 | 不能直接接 PR gate,要双跑对比 | 后端团队 | 2 周后 |
| 5 | GitOps / Argo CD | ❌ 仅文件骨架 | 缺 K8s + Argo CD + ops repo + 5 类 secret | ops/SRE | 不定 |

---

## #1 GHA cache — 待 CI 验证

**已完成**:
- `.github/actions/setup-build-env/action.yml` 加 testcontainer image cache(4 image: kafka/postgres/redis/minio)
- Maven cache 由 `setup-java@v4 cache=maven` 自动覆盖

**待验证**(下次 push 后):
1. Actions UI → 任一 job → 看「Cache testcontainer Docker images」step
   - 首次:`cache hit: false`,跑 Pull and save(~2 min)
   - 第二次:`cache hit: true`,跑 Restore from cache(~30s)
2. 对比同 job wall-clock:期望 cold 14min → warm 10-11min(节省 ~3min)

**回退方案**:
- cache step 失败会自动 fallback 到 testcontainers 自己拉(`|| true` 兜底)
- 若 cache key 拼写错导致永远 miss,删 `.github/actions/setup-build-env/action.yml` L36-58 的 3 个新 step 即可

---

## #2 Branch protection — 配置在但 GitHub 锁 enforcement

**已完成**:
- `https://github.com/pinpols/file-batch-system/rules` 创建 ruleset `main protection`
- Active 状态,4 个 required checks(static-checks / unit-it-a / unit-it-b / security-scan)
- Require PR + 1 approval,Dismiss stale,Conversation resolution,Block force pushes 都勾

**阻塞**:
> Your rulesets won't be enforced on this private repository until you move to GitHub Team organization account.

私有仓 + 免费个人账号下 GitHub **强制 bypass** —— 已实测 force push 不拦截。

**生效条件(任一)**:
1. **升级 GitHub Team org**($4/user/月) — 需把 repo 转到 Team org 下
2. **Repo 转 public** — 暴露代码,商业敏感时不可取
3. ~~维持现状(discipline only)~~ — 当前选择

**跟进时机**:
- 团队扩到 ≥ 3 人时(单人开发自律够用)
- 准备商业化时(必须配 enforcement)
- 出现一次 main push 事故时(遇到问题后必上)

**升级时无需重做**:ruleset 配置已存,Team org 切换后自动激活。

---

## #3 Dependabot — 待首次扫描

**已完成**:
- `.github/dependabot.yml` — 3 ecosystem(maven / actions / docker),maven 分 5 组
- `.github/workflows/dependabot-auto-merge.yml` — patch 自动 approve+merge;minor 只 approve

**首次扫描时机**:
- 自动:**周一 03:00 Asia/Shanghai**(下周一 2026-05-25)
- 手动:https://github.com/pinpols/file-batch-system/network/updates → 每个 ecosystem 旁点 `Check for updates`,1-5 min 后看 PR 列表

**待验证**:
1. PR 列表出现 `chore(deps): bump spring-boot...` 类 PR
2. 走完 pr-gate.yml 4 个 required check 后,patch PR 应该被 auto-merge workflow 自动 merge
3. major bump 不应该出 PR(被 dependabot.yml ignore 规则过滤)
4. 分组生效:同组多 dep 升级合一个 PR(不会 N 个 PR 刷屏)

**潜在风险**:
- patch auto-merge 在 CI 不稳时会 merge 进有 bug 的 patch — pr-gate 必须真稳才开
- minor 只 approve 等人工:第 1 周可能堆 5-10 个 PR,需安排时间清

---

## #4 TIA POC — 影子模式 2 周

**已完成**:
- `scripts/ci/select-affected-tests.py`(228 行)+ `.sh` wrapper
- 验证:S1 改 service 选 1/486(99.8% 缩减)/ S4 改 common util 选 379/486(22% 缩减)
- `docs/runbook/tia-poc-2026-05-22.md` 灰度上线方案

**不能直接接 PR gate**:
- 静态分析盲区(Spring DI / 反射 / SPI / MyBatis XML / Flyway / yml / 注解处理器)
- 漏判一次 = PR 跑通 main 跑挂

**3 阶段灰度**:

| 阶段 | 时长 | 动作 | 退出条件 |
|---|---|---|---|
| **Phase 1 — 影子模式** | 2 周 | PR gate 加 step 跑 `select-affected-tests` 输出列表到 log,但**实际还跑全套** | 收集 50+ PR,对比「TIA 选中 set」与「真正失败的测试 set」,漏判 < 5% |
| **Phase 2 — 双跑校验** | 2 周 | 加 job 用 TIA 选中跑,同时全套也跑;两者结果都上传 | 双跑结果一致率 > 95% |
| **Phase 3 — primary** | 持续 | PR gate 改为只跑 TIA 选中 + module 粒度 fallback | 出 1 次 main 红 → 回退 Phase 2 |

**Owner**:后端团队;每周 1 次 review TIA 漏判率

---

## #5 GitOps / Argo CD — 仅文件骨架

**已完成(文件)**:
- `.github/workflows/build-image.yml`(97 行) — push main 触发,7 模块 build+push ghcr.io
- `.github/workflows/promote-staging.yml`(73 行) — auto-PR 改 ops repo
- `helm/batch-platform/templates/canary-console.yaml`(57 行) — flagger Canary
- `helm/batch-platform/values-canary.yaml`(48 行) — canary 模式 values
- `docs/runbook/gitops-onboarding-2026-05-22.md`(166 行)— ops 接入手册
- `docs/runbook/release-process-archived-2026-05-22.md`(已归档,权威源为 `releasing.md`)— 历史发布流程蓝图

**严重不能验证**:整套 GitOps 还没接 infrastructure,**push main 不会触发任何部署**。当前 build-image.yml on push 已经存在但 ghcr push 会因没配 `GHCR_TOKEN` secret 而失败。

**5 步 ops 接入清单**(按顺序执行):

1. **创建 ops repo** `pinpols/file-batch-system-ops`
   - 目录结构(模板见 `gitops-onboarding-2026-05-22.md` §2):
     ```
     argo/staging-application.yaml
     argo/prod-application.yaml
     helm/values-staging.yaml
     helm/values-prod.yaml
     ```

2. **配 GitHub secrets** 在 `pinpols/file-batch-system`:
   - `OPS_REPO_TOKEN`:PAT 写权限到 ops repo
   - `GHCR_TOKEN`:ghcr.io push 权限(或用默认 `GITHUB_TOKEN` + `packages: write` permission)
   - 修改 `build-image.yml` 的 `REGISTRY_NAMESPACE` 默认值 `pinpols` 跟实际对齐

3. **K8s 集群准备**(假设阿里 ACK / GKE / 自建 K3s):
   - 安装 Argo CD(`helm install argo-cd argo/argo-cd`)
   - 安装 flagger(`helm install flagger flagger/flagger`)
   - 安装 kube-prometheus-stack(flagger 需要 metric source)
   - 安装 nginx ingress(或 istio)
   - 每个 namespace 创建 `ghcr-pull` imagePullSecret

4. **kubectl apply Argo Application**:
   - `kubectl apply -f file-batch-system-ops/argo/staging-application.yaml`
   - `kubectl apply -f file-batch-system-ops/argo/prod-application.yaml`(prod 设 manual sync)

5. **end-to-end smoke**:
   - 推 main 一个 no-op commit
   - 看 build-image GHA job 绿 + image push ghcr.io 成功
   - 看 ops repo 自动出 PR(`auto: promote <sha> to staging`)
   - merge ops PR → Argo CD UI 显示 syncing
   - 7 个 Deployment 在 staging 全部 Ready
   - canary-console flagger 自动 ramp 10/20/.../50%,metric 守护过

**Owner**:SRE / ops 团队;
**预计**:5 天 ops 投入 + 1 天联调
**不动作时影响**:本地 + docker-compose 开发不受影响,但缺生产灰度发布能力,出问题只能手动 helm rollback

---

## 总结

5 项的实际可用状态:

```
完全可用:    无
等 CI 验证:  #1 GHA cache(下次 push 自然验)
配置就位:    #2 Branch protection(等升级生效)
等首次扫描:  #3 Dependabot(本周一 03:00 自动)
影子模式期:  #4 TIA(2 周后看漏判率)
骨架就绪:    #5 GitOps(等 ops 5 步接入)
```

下一步 owner 行动:
- **后端**:#4 影子模式监控(2 周后 review)
- **ops/SRE**:#5 GitOps 5 步接入(优先做 staging,prod 看灰度)
- **管理**:#2 GitHub plan 升级评估(团队扩前)
