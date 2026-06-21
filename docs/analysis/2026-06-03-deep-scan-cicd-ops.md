# Deep Scan — CI/CD + 部署 + 监控 + 运维 (2026-06-03)

> Branch: `docs/deep-scan-cicd` · base `origin/main` @ `4d47f332`
> Scope: `.github/workflows/**`、Maven 构建、Docker、Helm、docker-compose、observability(Prometheus/Grafana/Loki/Tempo/OTel)、Alertmanager、logback、release/DR/on-call runbook 覆盖度
> Out of scope: 业务代码评审、SDK 契约本身(见 `2026-06-03-deep-scan-summary.md` 之姐妹文档)

本文为「全方位深度扫描 batch1」11 lane 中 **CI/CD + Ops** lane 的产出。规则:每条
issue 给 P0/P1/P2 + 证据(行号/文件)+ 修复建议。本扫描发现 **3 P0 / 11 P1 / 9 P2 / 3 nit**,
其中 P0 全部围绕「文档引用了不存在的 workflow / runbook」与「prod 默认值与 launch 文档矛盾」
两类,改一行即可的轻量级修复。

---

## 0. 全景拓扑

```
                ┌─────────── PR / push 触发 ────────────┐
                ▼                                       ▼
  ┌─────────────────────────┐         ┌────────────────────────────┐
  │  pr-gate (PR + queue)   │         │  full-ci-gate (main push)  │
  │   static / unit×3 / sec │         │   static / unit+IT×3 /     │
  └─────────────────────────┘         │   security / e2e shard×4   │
                                      └────────────────────────────┘
            ┌──── ─── ─── ─── ─── ─── ────┐
            │                              │
            ▼                              ▼
  ┌────────────────────┐         ┌────────────────────┐
  │  staging-gate      │         │  codeql            │
  │  nightly e2e×4     │         │  weekly + PR SAST  │
  └────────────────────┘         └────────────────────┘

       ┌── 旁路 workflow ──────────────────────────────┐
       │  sdk-python         (lint + smoke + contract) │
       │  sdk-contract-parity(JS↔Py↔fixture 三联校验) │
       │  sdk-python-publish (tag → PyPI)              │
       │  strict-verify      (dry-run smoke + manual)  │
       │  workflow-lint      (actionlint + zizmor)     │
       │  dependabot-auto-merge / label-automerge      │
       └───────────────────────────────────────────────┘
```

11 workflow 全清单(`.github/workflows/*.yml`):
`codeql.yml / dependabot-auto-merge.yml / full-ci-gate.yml / label-automerge.yml /
pr-gate.yml / sdk-contract-parity.yml / sdk-python-publish.yml / sdk-python.yml /
staging-gate.yml / strict-verify.yml / workflow-lint.yml`

**关键观察**:**没有** `build-image.yml` / `promote-staging.yml` / `release-please.yml`。
这是后续 P0 的核心证据。

---

## 1. P0 — 必须本周改

### P0-1 `docs/runbook/release-process-2026-05-22.md` 引用的核心 workflow 全部不存在

**证据**

* `docs/runbook/release-process-2026-05-22.md:33-44` 描述 `build-image.yml` 在 main push
  时构建 7 个模块镜像并推 `ghcr.io/pinpols/<module>:sha-<long-sha>`。
* `docs/runbook/release-process-2026-05-22.md:46-49` 描述 `promote-staging.yml` 自动到
  ops repo 提 PR。
* 实际 `.github/workflows/` 下既无 `build-image.yml` 也无 `promote-staging.yml`。
* 文档 line 2 自己异常退出 ⚠ 提示「promote-staging.yml 已删除」,但**没有说 build-image.yml 也删了**;
  并且这个提示语义「本文档保留作为未来恢复 GitOps 时的参考蓝图」**不足以拦截新人误用**(读者
  会以为「build 镜像还跑,只是 promote 没自动」)。
* `.github/workflows/workflow-lint.yml:71` 注释里写「老 workflow(dependabot-auto-merge /
  build-image / capacity-gate 等)有 cache-poisoning / dangerous-triggers / bot-conditions
  等 pre-existing finding」—— 也是把 `build-image` 当现存的来谈。

**影响**:发布流程文档「面向当下」的链路名义上需要 build-image,实际整个 main push 后
**没有任何 workflow 会产出 docker 镜像**。如果有人按文档执行,会卡在第一步;如果走 ArgoCD/
Helm 直接拉 `appVersion: 1.1.0-SNAPSHOT`,registry 没这个 tag,deploy 直接挂。

**修复**(任选其一):
1. **快**:把 `release-process-2026-05-22.md` rename 成 `release-process-archived-2026-05-22.md`
   或在 doc 第一行加红色「⚠ 全文已废弃,当前发布走 docs/runbook/releasing.md」。
2. **正**:补一个最小的 `build-image.yml`(main push 触发 `docker buildx bake`,推
   ghcr 或 aliyun ACR);Chart.yaml 的 `appVersion: 1.1.0-SNAPSHOT` 同步改成稳定版本号。
3. **同时**清掉 `workflow-lint.yml:71` 里对 `build-image` 的引用,改成「老 zizmor finding
   只压本 PR 新增 workflow,后续治理 tracking 见 docs/runbook/ci.md TODO」。

### P0-2 `helm/batch-platform/Chart.yaml` appVersion 默认 `1.1.0-SNAPSHOT`

**证据**:`helm/batch-platform/Chart.yaml:9` `appVersion: "1.1.0-SNAPSHOT"`。
`values.yaml:7` 的 image fallback 写「tag 留空时 fallback 到 Chart.appVersion;Chart.appVersion
跟随**最近一次 GA 版本**(非 main 分支的 SNAPSHOT)」。这两个声明**自己打架**。

**影响**:`helm install` 不显式 `--set image.tag=...` 时,直接拉 `<module>:1.1.0-SNAPSHOT`。
SNAPSHOT tag 在多数 registry 是 mutable;并且 `docs/runbook/releasing.md` 第 0 节起就强调
单点 `<revision>` —— Chart.appVersion 漂在 SNAPSHOT 上说明**没人执行**「改 pom <revision>
→ 改 Chart.yaml appVersion → build 镜像 tag」的流程。

**修复**:接到 release-please(详见 P1-1)前,把 appVersion 直接 pin 到最近一次实际 build 出
的稳定 tag(比如 `1.0.4`),或者把 image tag 默认值改成 `required` 让 deploy 必须显式传入。

### P0-3 strict-verify 在 CI 上**永远不阻断**,跟 user MEMORY 一致但代码里 dual-mode 易误读

**证据**:`.github/workflows/strict-verify.yml:131-138` `Run strict-verify (live, non-blocking)`
step `exit 0` 硬写,后面再加 `continue-on-error: true`,**双重兜底等于永不挂红**。
另外整个 workflow 没接入 main push / PR required check,user MEMORY
`project_strict_verify_local_only.md` 明确「严格校验只走本地+dispatch,不接 main push / PR 门禁」
是预期行为。

**为何仍 P0**:工作流本身 OK,但**容易让人误以为有兜底**。`scripts/local/strict-verify.sh`
非 0 退出能被 `set +e` + `exit 0` 完整捕获并抑制,即使 docker compose 起 PG 失败、jar 起不来,
CI 也会报绿。一旦后续有人加 required check `strict-verify-live`,会直接零阻断地通过 —— 隐性
回归风险。

**修复**:把 `continue-on-error: true` 删掉(留 `exit 0` 的一层就够),或者直接改成 `exit $rc`
并把 job 标 `if: ${{ false }}` 防止误启用。同时在 `docs/runbook/ci.md` 加段「strict-verify 是
opt-in,改成阻断的步骤」。

---

## 2. P1 — 两周内改

### P1-1 没有 release-please / 任何 release 自动化

**证据**:整个 `.github/workflows/` 没有 release-please。`docs/runbook/releasing.md` 第 0 节起
全篇手动 `mvn versions:set-property` + `git tag` + 手写 `CHANGELOG.md`。`sdk-python-publish.yml`
是 tag push 触发,但 tag 仍要人手打。`CHANGELOG.md` 是手动维护(根目录 21KB)。

**影响**:发布频次低 → CHANGELOG 必然漏条目、appVersion 漂(见 P0-2)、tag 名跟 pom revision
不同步。当前看 CHANGELOG 最近一条对应 1.0.4 但 appVersion 是 1.1.0-SNAPSHOT —— 已经偏。

**修复**:接 [`googleapis/release-please-action`](https://github.com/googleapis/release-please-action),
config 用 `release-please-config.json` 把根 pom `<revision>` 当 manifest source,挂 main push
触发;同步生成 PR + tag + GitHub Release。FE 单独走 `release-as` strategy。需 0.5 人天。

### P1-2 paths-filter 在 pr-gate 跟 SDK rename 不同步

**证据**:`.github/workflows/pr-gate.yml:78-86` `changes` job 的 paths-filter:
```
code:
  - 'batch-*/**'
  - 'batch-worker-sdk-python/**'
```
注意已经显式列了 `batch-worker-sdk-python`,但 `batch-*/**` 已经命中所有 batch- 开头模块,
重复 — 不算 bug,但说明 rename 后 list 没收拾干净。

更严重:`sdk-python.yml:9-13` paths:
```
- 'batch-worker-sdk-python/**'
- 'docs/api/sdk-contract-fixtures/**'
- '.github/workflows/sdk-python.yml'
```
但 working-directory 用的是 `sdk-python`(line 39 `defaults.run.working-directory: sdk-python`),
**目录名完全不一致**!实际仓库目录是 `batch-worker-sdk-python/`,根本没有 `sdk-python/`。
意味着 `pip install -e .[dev]` / `ruff` / `mypy` / `pytest` 全部会 `cd` 不存在的目录失败。

`sdk-contract-parity.yml:55-72` 同样问题:`python-contract` job `working-directory: sdk-python`。

**影响**:任何 Python SDK PR 都会让 sdk-python / sdk-contract-parity 两个 workflow 直接挂红。
但 user 在 MEMORY 标了「项目已验证能力」—— 说明实际 sdk-python 路径可能 CI 上**真的没跑过**,
或者前述 workflow 实际没启用 / 改了名我看到的是旧版本。

**修复**:`sdk-python.yml` / `sdk-contract-parity.yml` 里 `working-directory: sdk-python`
全改 `batch-worker-sdk-python`;同步 `cache-dependency-path` 也已经是 `batch-worker-sdk-python/pyproject.toml`
能对上,只剩 working-directory 漂。

### P1-3 full-ci-gate paths-ignore 漏过真改动:`db/migration/**` / `helm/**` 都不在 ignore — 但 pr-gate 的 changes filter 也把这些纳入 `code` —— **没问题但 pr-gate 自身 paths-ignore 漏**

**证据**:`pr-gate.yml:39-48` push 事件 `paths-ignore` 列:
```
- 'docs/**(细分多条)'
- '*.md'
- '.github/workflows/**'   ← !!! 
```
最后一行把 `.github/workflows/**` 标为 ignore,理由注释「workflow yml 改动由 workflow-lint /
codeql / actionlint / zizmor 独立 workflow 覆盖,不需要 pr-gate 重跑 maven」—— 但
**改 workflow 是高风险操作**,正应该让 maven 跑一遍证明改 workflow 没破构建。当前 setup
意味着我可以把 unit-it-a job 改成 `run: true` 不触发 pr-gate(只触发 workflow-lint —— 它不跑测试),
直接绕过 gate。

CODEOWNERS 强制了 `@pinpols` review (`/.github/workflows/` line 15),但 paths-ignore 仍是
隐患——如果有 admin override,绕过路径就在那。

**修复**:从 `pr-gate.yml` paths-ignore 删掉 `.github/workflows/**` 和 `.github/actions/**`。
反正 pr-gate `changes` job 还会用 paths-filter 二次判定。

### P1-4 release-please / image build 缺位让 Helm appVersion / image tag 链路彻底空挡

**证据**:见 P0-1 + P0-2 + P1-1 合并影响。

**修复**:把 P1-1 release-please 接上后,在它生成的 release PR 里自动 bump `Chart.yaml`
appVersion 跟 root pom `<revision>` 同步;并加 `build-image.yml` 在 release tag 触发时
buildx bake。

### P1-5 没有任何 backup / DR / restore runbook

**证据**:`docs/runbook/` 60 篇,grep `backup|disaster|recover|PIT|restore`:0 命中(只
`script-deploy.md` 提到 "backup" 一次,做的是 helm rollback,不是 DB)。

`pg-primary-failover.md`(P0 playbook)只讲流复制切主,**没有 PIT 恢复**;Kafka offset 重置
也只在 `playbooks/kafka-rebalance-stuck.md` 当 troubleshooting 提,不是 DR SOP。

**影响**:真发生 DB drop / outbox 表损坏 / Kafka topic 数据丢失,操作手册等于零。这种事
RPO/RTO 都没法定。

**修复**:补 `docs/runbook/disaster-recovery.md`,至少覆盖三场景:
1. PG PIT 恢复(pg_basebackup + WAL replay 步骤)
2. Outbox 重投(SQL 模板:把 `outbox_event` 选定时间窗口 reset 到 PENDING)
3. Kafka offset 重置(各 consumer group `kafka-consumer-groups.sh --reset-offsets` 参数表)

### P1-6 没有 on-call / 告警升级 runbook;Alertmanager 全部指向 dummy webhook

**证据**:`docker/observability/alertmanager-batch-template.yml:23-46` 全部 receiver:
```
url: http://host.docker.internal:19001/alert
url: http://host.docker.internal:19001/pager
url: http://host.docker.internal:19001/dispatch
...
```
都是 `host.docker.internal:19001` 同一个 dummy 端口,**没有任何 Slack / PagerDuty / 短信
webhook**。`docs/runbook/` 无 `on-call.md` / `escalation.md` / `paging.md`。

`prometheus-batch-rules.yml` 共 37 个 severity label,定义齐(critical/warning),但分级
背后**没有 runbook 告诉值班怎么响应**。`incident-response.md` 只给 5 个 playbook,严重程度
分级表(P1/P2/P3)定义跟告警规则的 severity(critical/warning)也对不上。

**影响**:告警发出来没有真实 channel。即便 Grafana 看到红条,值班不知道找谁。

**修复**:
1. `alertmanager-batch-template.yml` 改成真实 Slack incoming webhook + PagerDuty integration key,
   用 helm secret / sealed-secret 注入。
2. 补 `docs/runbook/on-call-rotation.md`(轮班表 + 升级路径)。
3. `incident-response.md` P1/P2/P3 跟 Alertmanager critical/warning 一一对应(2 级映射 3 级、
   或反过来),写在分级表里。

### P1-7 logback trace context 只在 batch-defaults.yml,console-api logback-spring.xml 不含 traceId / tenantId

**证据**:
* `batch-common/src/main/resources/batch-defaults.yml:170` console pattern 含
  `%X{service:-} %X{tenantId:-} %X{traceId:-} %X{requestId:-} %X{jobInstanceId:-} %X{fileId:-}`,
  ✓ 全。
* `batch-console-api/src/main/resources/logback-spring.xml` `grep -c "traceId"` = 0,
  `grep -c "tenantId"` = 0。pattern 写的是 `[%X{frontendApp}][%X{frontendUserId}][%X{frontendPage}]`,
  只覆盖 FE 上报字段。
* `observability-stack.md` 的「Logs → Traces (Loki derivedFields)」用 regex
  `"traceId"\s*:\s*"([a-f0-9]{16,32})"` 抓 traceId —— 但 console-api 日志根本不输出 traceId,
  Loki 这条 derivedField 在 console-api 日志上**100% 不会命中**。

**修复**:`batch-console-api/src/main/resources/logback-spring.xml` line 16/37 的 pattern
追加 `[%X{traceId:-}][%X{tenantId:-}]`,或者直接 include `batch-defaults` 的 pattern 复用。

### P1-8 codeql `continue-on-error: true` 永久 dump

**证据**:`.github/workflows/codeql.yml:73-79` Analyze step 挂 `continue-on-error: true`,
注释说「未开启 SARIF upload 会报 403,开启后移除本行即可恢复硬阻断」。但仓库注释 2026-05-23
打的,现在 2026-06-03,已经过了 12 天。需要确认仓库 Code Scanning 是否已开,若已开就摘掉
`continue-on-error`,否则 SAST 等于装饰品。

**修复**:验证 Code Scanning 是否启用,启用了立刻摘 `continue-on-error`。

### P1-9 dependabot-auto-merge 跟 label-automerge 有 race 风险

**证据**:`dependabot-auto-merge.yml:25-29` 对 patch bump 跑 `gh pr merge --auto --squash`,
依赖 GitHub auto-merge feature。`label-automerge.yml:1-30` 注释明确说「personal repo 不支持
bypass_pull_request_allowances → auto-merge 永远卡」—— 所以新写了 label-based admin merge。
**但 dependabot 路径还用 `--auto`**,意味着 dependabot patch PR 也会卡。

**影响**:dependabot PR 自动 approve 后死等 `--auto` 触发,实际仓库设置让它永远不触发。

**修复**:`dependabot-auto-merge.yml` 改成给 PR 打 `automerge` label,复用 label-automerge
admin merge 路径;或者 dependabot job 也走 `gh pr merge --admin --squash` 路径。

### P1-10 docker-compose.app.yml 与 helm prod 配置漂移没有 lint

**证据**:`pr-gate.yml:118` `Verify yml ↔ docker-compose default sync` 跑了
`scripts/ci/check-config-defaults-sync.py --check`,但只对 `batch-defaults.yml` ↔
`docker-compose.yml` 同步;**没有对** `helm/batch-platform/values.yaml` 跟
`batch-defaults.yml` 跟 `docker-compose.app.yml` 三方对齐扫描。

举例已知漂(`docker-compose.app.yml:62`):`SPRING_FLYWAY_ENABLED: "false"` —— compose
里 console-api 这个 service 显式关 flyway(因为有别的 service 跑迁移);helm 里没看到同一
开关,意味着 K8s deploy 时 console-api 可能再跑一遍 flyway → 并发 Flyway 锁竞争。

**修复**:扩展 `check-config-defaults-sync.py` 加 helm values.yaml 维度。

### P1-11 Dockerfile.app 基础镜像跟 Maven builder 版本错配

**证据**:`docker/Dockerfile.app:13` `FROM maven:3-eclipse-temurin-26 AS builder`(JDK 26)
`docker/Dockerfile.app:39` `FROM eclipse-temurin:25-jre-jammy`(JDK 25)。
build 用 26、runtime 用 25。

**影响**:`mvn package` 用 JDK 26 toolchain,产物 class file version 默认 65(JDK 21+;
但要看 release config)。root pom 显示 `<java.version>` 是 25 —— 如果 mvn 严格 release 25,
不会出错;但 builder 镜像本身用 26 会触发 Maven 一些 26-only 行为(比如 sealed/preview API),
跑 runtime 25 上挂的概率从「永远不会」变「可能」。Hadolint / Trivy 也会标基础镜像不一致。

**修复**:builder 改 `maven:3-eclipse-temurin-25` 跟 runtime 一致;或者 runtime 升 26。

---

## 3. P2 — 月度可接受

### P2-1 codeql 跟 full-ci-gate 都跑 mvn compile,串了两遍

`codeql.yml:62` 跑 `mvn -B -ntp -DskipTests -pl '!batch-e2e-tests' compile`,
`full-ci-gate.yml` 三个 unit-it 也 compile。可以让 codeql 复用 `setup-build-env` 的 m2
cache,但实际 codeql job 是独立 runner —— 接受成本。

### P2-2 PR draft 时 changes job 仍跑

`pr-gate.yml:69` `changes` job 没有 `if: github.event.pull_request.draft == false` 拦截;
draft PR 也消耗 1 个 runner-min。每月几十 PR 量级,接受。

### P2-3 staging-gate 跟 full-ci-gate 的 e2e shard 列表手动同步

`staging-gate.yml` 跟 `full-ci-gate.yml` 各自 hardcoded shard 1-4 的 test class 列表;
LPT 数据老化时两边要双改,容易漂(staging-gate 显示 AtomicTask…,full-ci-gate 写
SpiTask… —— 已经漂了,看 line 名字)。

**证据**:`staging-gate.yml:64-71` 写 `AtomicTaskPipelineE2eIT`,
`full-ci-gate.yml:732-738` 写 `SpiTaskPipelineE2eIT`。这两个 IT 类名应该是同一个。
确认下 IT 类是 `AtomicTaskPipelineE2eIT` 还是 `SpiTaskPipelineE2eIT` —— 至少其中一个
workflow 跑的是不存在的类,靠 `-Dsurefire.failIfNoSpecifiedTests=false` 静默跳过 = 静默
漏测。

**修复**:把 shard 配置抽到 `.github/workflows/shards.yml` 用 `${{ fromJSON }}` 复用,
或者 surefire-failIfNoSpecifiedTests 在 main 改成 `true`(让漏掉的 IT 让 CI 红)。

### P2-4 helm worker-atomic networkpolicy 默认 disabled,实际上 dual-use 启用就该强制

ADR-029 worker-atomic 显式说 shell/sql/stored-proc 能力等于 RCE。
`worker-atomic-networkpolicy.yaml:14` 仍然 `workerAtomic.networkPolicy.enabled` 默认 false。
P0 应该是「dual-use 启用 → 自动启用 NetworkPolicy」,而不是 ops 手动 opt-in。

### P2-5 prometheus rules 缺 `runbook_url` annotation

`prometheus-batch-rules.yml` 每条 alert 有 summary + description,**没有 runbook_url**。
Alertmanager → Slack 发出来值班点不进 runbook,得手动到 docs/runbook/playbooks 找。

**修复**:每条 alert 加 `annotations.runbook_url: https://internal-wiki/.../playbooks/...`。

### P2-6 grafana dashboard JSON 没纳入 lint

`docker/observability/grafana-dashboard-batch.json` 跟 `grafana-dashboard-batch-coverage.json`
是手 export 的 raw JSON。CI 无任何校验它跟 prometheus rule 引用的 metric name 一致性。
metric rename 后 dashboard 静默坏掉。

### P2-7 helm probes initialDelay 写死 30/40s

`values.yaml:135-144` `livenessProbe.initialDelaySeconds: 40`,`readinessProbe: 30`。
对 worker JVM 启动 60s+ 不安全。已经在用 `values-startup-probes.yaml` overlay,但 default
应该用 startupProbe 模式而非 long initialDelay。

### P2-8 dependabot vs renovate 都启用

`.github/dependabot.yml` + `.github/renovate.json` 同时存在。两个 bot 抢同一组依赖,会出
重复 PR / merge 冲突。`renovate.json` `assignees: ["idengzhao"]` 是旧账号(CODEOWNERS 已经
迁到 `@pinpols`)。

**修复**:二选一,推荐留 renovate(group 策略更灵活),删 `dependabot.yml`。

### P2-9 dependabot-auto-merge `if: github.actor == 'dependabot[bot]'` 不防伪

`dependabot-auto-merge.yml:9` 这种条件可以被 fork PR 触发(攻击者命名为 `dependabot[bot]`
不太可能,但 PR title spoof + action_url 检查更安全)。zizmor 应该已经在治理 backlog。

---

## 4. nit

* nit-1: `pr-gate.yml:128` 注释段 `Job 2: unit-shard-1`,实际 job 名是 `unit-it-a`,
  注释跟 job name 漂。
* nit-2: `docs/runbook/release-process-2026-05-22.md` 有 ⚠ 标 deprecated 但文件名没改,
  搜索还是搜得到;rename + redirect note 更安全。
* nit-3: `helm/batch-platform/templates/secret.yaml:13-18` 用 `{{ fail }}` 强制长度
  ≥ 16,但 helper 没把 `internalSecret` 跟 `consoleJwtSecret` 抽公共 template;再加一个
  字段就要复制粘贴一遍。

---

## 5. 已验证正面项(避免重复 audit)

* `pr-gate.yml` C 方案分级 + Merge Queue + paths-filter docs-only 跳 unit 路径,设计稳。
* `full-ci-gate.yml` 4 shard e2e 并发 + 上传 surefire/failsafe artifacts,工程化扎实。
* `codeql.yml` 用 `security-extended` query 集 + `persist-credentials: false` zizmor 治理。
* `workflow-lint.yml` 双层(actionlint + zizmor)秒级反馈。
* helm `worker-atomic-networkpolicy.yaml` ADR-029 egress 三段(DNS / PG / Kafka)默认就有,
  开关一开即生效,实现质量高。
* `helm/batch-platform/templates/secret.yaml` 用 `{{ fail }}` 在 install 时硬阻断弱密钥,
  比运行时 fail-fast 早一拍。
* observability stack 三件套 docker compose 一键起,`observability-stack.md` 整合 4 个老
  runbook 成单页 SOP,对新人很友好。
* `incident-response.md` + `playbooks/` 5 篇核心 P0/P1/P2 场景齐(PG 切主 / Redis 全断 /
  Kafka rebalance / Outbox 卡 / batch-day 不结算),覆盖度比同期项目高。
* Dockerfile.app 共享 builder + per-image runtime + m2 cache mount,9 模块从 15-20 min
  压到 3 min 增量,设计漂亮。
* Helm orchestrator StatefulSet + headless service + entrypoint.sh ordinal → SHARD_INDEX
  注入,匹配 outbox 静态分片语义。
* Helm `tenant-isolation.yaml` Phase D per-tenant Secret/NetworkPolicy/ResourceQuota 模板
  齐,worker-tenant.yaml envFrom 覆盖共享 secret 同名 key,真有「per-tenant 凭据隔离」能力。

---

## 6. 修复路线图

| Sprint | 项 | 工作量 |
|---|---|---|
| 本周 | P0-1 release-process 文档 deprecate 改名 + workflow-lint 注释更新 | 0.5 h |
| 本周 | P0-2 Chart.yaml appVersion 改实际版本 / 加 `required` | 0.5 h |
| 本周 | P0-3 strict-verify exit 0 改 exit $rc | 15 min |
| 本周 | P1-2 sdk-python working-directory 改回正确目录 | 30 min |
| 本周 | P1-3 pr-gate paths-ignore 删 `.github/workflows/**` | 5 min |
| 两周 | P1-1 接 release-please | 0.5 d |
| 两周 | P1-5 写 disaster-recovery runbook | 1 d |
| 两周 | P1-6 Alertmanager 接真实 channel + on-call rotation runbook | 1 d |
| 两周 | P1-7 console-api logback pattern 加 traceId/tenantId | 15 min |
| 两周 | P1-8 codeql 启用 Code Scanning + 摘 continue-on-error | 30 min |
| 两周 | P1-9 dependabot 走 label-automerge 路径 | 30 min |
| 两周 | P1-10 扩 check-config-defaults 校验 helm | 0.5 d |
| 两周 | P1-11 Dockerfile builder/runtime JDK 对齐 | 15 min |
| 月度 | P2-1..P2-9 batch 治理 | 见各条 |
| nit | nit-1..nit-3 顺手改 | 30 min total |

---

## 附录 A · 全 workflow 触发面对照表

| Workflow | push main | PR | merge_group | schedule | workflow_dispatch | 触发面备注 |
|---|---|---|---|---|---|---|
| pr-gate | branches-ignore main | ✓ | ✓ | — | ✓ | C 方案 unit-only + e2e 全交 staging |
| full-ci-gate | ✓ (paths-ignore docs) | — | — | — | ✓ | main push 兜底 unit+IT+e2e |
| staging-gate | — | — | — | nightly 18:00 UTC | ✓ | 全量 26 个 E2E |
| codeql | ✓ | ✓ | — | weekly Mon 03:00 | — | SAST |
| sdk-python | ✓ (paths) | ✓ (paths) | — | — | ✓ | Python SDK CI |
| sdk-contract-parity | — | ✓ (paths) | — | — | ✓ | JS↔Py↔fixture 三联 |
| sdk-python-publish | tag `sdk-python-v*` | — | — | — | ✓ | PyPI 发布 |
| strict-verify | branches-ignore main + paths | ✓ (paths) | — | — | ✓ | dry-run smoke,live opt-in |
| workflow-lint | ✓ (paths) | ✓ (paths) | — | — | — | actionlint + zizmor |
| dependabot-auto-merge | — | ✓ | — | — | — | bot 专用 |
| label-automerge | — | ✓ + label | — | — | — | + workflow_run completed |

**漏区**:
* 镜像构建(本应 push main 触发 docker build push)— **0 workflow** 覆盖
* 部署/promote — **0 workflow** 覆盖
* release tag 生成 — **0 workflow** 覆盖(只有 sdk-python-publish 在 tag 已存在后 publish)

## 附录 B · alert 规则到 runbook 的映射缺口

| Alert | severity | 对应 playbook? |
|---|---|---|
| BatchServiceDown | critical | ✗ 无 |
| BatchDispatchCircuitsOpen | warning | ✗ 无 |
| BatchDispatchFailureRateHigh | critical | ✗ 无 |
| BatchHeapUsageHigh | warning | docs/runbook/jvm-tuning-and-profiling.md 间接 |
| BatchKafkaConsumerLagHigh | warning | playbooks/kafka-rebalance-stuck.md ✓ |
| BatchTaskClaimLatencyHigh | warning | ✗ 无 |
| BatchOutboxPublishLatencyHigh | warning | playbooks/outbox-stuck-publishing.md 部分 |
| BatchPipelineStepExecutionLatencyHigh | warning | ✗ 无 |
| BatchRedisMemoryUsageHigh | warning | playbooks/redis-shedlock-down.md 间接 |
| BatchRedisConnectedClientsHigh | warning | ✗ 无 |
| BatchSlaViolationsPresent | warning | ✗ 无 |
| BatchAlertEventsGrowing | warning | ✗ 无 |
| BatchJobDefinitionFailingRepeatedly | warning | ✗ 无 |

13 条 alert 中 **9 条无任何 runbook 对应**。P1-6 修复时应顺手把每条 alert annotation 加
`runbook_url`,即便指向占位页都比无好。

---

> 本文档由 deep-scan agent 在隔离 worktree(`docs/deep-scan-cicd`)产出,
> 仅作 audit;具体修复以 follow-up PR 落地。
