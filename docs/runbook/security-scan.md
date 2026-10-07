# 本地安全扫描 Runbook

这份清单用于在提交 GitHub 之前做一次本地自检，覆盖最常见的 secret 泄露、依赖漏洞、代码级 SAST、镜像与文件系统扫描，以及 HTTP 动态探测。

统一入口优先使用 [scripts/ci/security-scan.sh](../../scripts/ci/security-scan.sh)。
这个脚本会先打包 [security-scan/](../../security-scan/) 独立 Java 模块，再调用外部工具完成扫描；Java 模块只负责编排，不重写扫描器。

## 一键入口

在仓库根目录执行：

```bash
bash scripts/ci/security-scan.sh
```

只跑某一类时,可直接透传参数：

```bash
bash scripts/ci/security-scan.sh --mode=secret
bash scripts/ci/security-scan.sh --mode=deps
bash scripts/ci/security-scan.sh --mode=sast
bash scripts/ci/security-scan.sh --mode=filesystem
bash scripts/ci/security-scan.sh --mode=image
bash scripts/ci/security-scan.sh --mode=dast --target-url=http://localhost:18080
```

需跳过打包时,也可直接调用 Java 模块：

```bash
mvn -f security-scan/pom.xml package
java -jar security-scan/target/security-scan-1.0.0.jar --mode=all --root=. --target-url=http://localhost:18080
```

## GitHub 安全告警治理

面向开发、代码审查、运维和发布负责人。Code scanning、Dependabot 与 Secret scanning 分开判读;本节用于后续增量治理。

### 处理与交付顺序

1. 核对仓库、默认分支、扫描提交和告警编号,记录规则、来源与危险调用;追踪调用方、数据表示、异常路径和已有防护。
2. 判定真实缺陷、已有防护的误报或证据不足。先跑恶意输入与正常对照,再在共享边界做最小完整修复。
3. 同步回归测试、配置/运行文档和变更日志。涉及 Console API、角色或租户契约时同步配对前端。
4. 定向测试、本地提交检查与适用 Sonar 检查完成后创建 PR,核验 PR CodeQL 的具体实例。
5. 用户合并后确认 main 扫描提交包含修复,查询 main 开放告警;合并与告警关闭均有证据后才写“收尾完成”。跳过、运行中或未运行须单独说明。

### 修复边界

- Workflow 执行成功只代表分析完成;必须查询对应提交与分支的开放告警,才能判断修复闭环。PR 扫描与 `main` 扫描分别核验。
- 用户可控日志字段复用 `LogSanitizer.value`,保留原字段与业务判断,覆盖 CR/LF 和 Unicode 换行。
- CodeQL 的日志模型同时支持换行常量模式(如 `\\R`)和合规白名单,不能把其中一种描述为唯一有效形态。验证码日志保留 `null` 归空串语义,CRLF 按共享规则归为一个 `_`;Import 通知继续保留更严格的领域日志白名单,不把它当作对象 Key 的输入校验。
- 私有落盘复用 `OwnerOnlyFiles`:按实际 `FileStore` 判断 POSIX 支持,创建时设文件 `0600` / 目录 `0700`;非 POSIX 必须验证 owner-only ACL,新建路径在写入内容前再次验证。不支持权限视图或权限设置失败时显式拒绝,不吞异常降级。
- Excel 导出的最终输出与 POI 中间 XML/模板 ZIP 都属于私有业务数据。SXSSF 整个生成和关闭过程使用线程局部 `TempFile.withStrategy`，复用 `PrivateTempFiles`，完成或异常后恢复原策略；不使用全局 POI 策略覆盖。输出写流不得隐式 CREATE 或跟随符号链接。检查临时根目录的磁盘空间及文件系统权限能力；不支持私有权限时按失败关闭处理，不回退共享临时目录。
- 私有临时文件、导出暂存与默认分发 Outbox 遵循上述约束。本地对象存储写入/复制的新文件也使用私有权限;显式配置的分发目标继续按目标存储协议与部署权限管理。
- 取证 ZIP 与 Atomic shell 工作根/任务目录同样在写入前应用私有权限。确定性根目录继续用于恢复和清理,不为消除告警改成每进程随机根;取证 ZIP 排他创建,打开输出流时不跟随符号链接。`batch.forensic.enabled` 默认 `true`,关闭后返回 503,生产须显式设置持久私有存储目录。
- `java/user-controlled-bypass` 若指向参数校验中的 `throw BizException.of(INVALID_ARGUMENT, ...)`,需追踪敏感调用实际语义。拒绝非法请求不是跳过鉴权;先验证 Controller 的 `@PreAuthorize`、认证过滤器、租户守卫和非法请求不落库,再以具体告警编号记录误报理由。不得删除校验来消除告警。

### 误报与后续守护

误报只按具体编号关闭并记录实际路径、防护和验证依据;接受风险使用不同状态并说明负责人、影响与后续处理。不得批量 dismiss,不得排除手写业务代码、降低规则或删除校验来清零。相关防护、依赖、调用链或规则变化后重新核查既有判断。

PR 与 main 维持现有 CodeQL 检查,新增/重开告警优先处理。新增自动守护先报告,再基线增量阻断;历史项按计划消化,不未经验证强制全仓失败。Secret scanning 涉及真实凭据时须撤销与轮换;删字符串不构成修复。Dependabot 核对可达性、锁定版本及 SBOM/许可门禁,不与 CodeQL 清零混称。

```bash
gh run list --repo pinpols/file-batch-system --workflow codeql --branch main --limit 3
gh api 'repos/pinpols/file-batch-system/code-scanning/alerts?state=open&ref=refs/heads/main&per_page=100' --paginate
```

每次记录告警编号、规则、修复提交、PR、定向验证、扫描提交、关闭状态与残余风险。配对前端遵循对应 `docs/runbook/security-alert-governance.md`,不复制后端权限和存储治理实现。

## GitHub 平台安全能力

仓库内扫描脚本之外，公共仓库应同时启用 GitHub Secret Scanning、Push Protection、Dependabot security updates 和 Private Vulnerability Reporting。non-provider patterns 与 validity checks 仅在当前 GitHub 计划和仓库能力支持时启用；不可用时由现有 Gitleaks/CodeQL 和人工响应补位。平台设置是运行事实，不能只凭文档、workflow 或 `dependabot.yml` 推断已开启。

OpenSSF Scorecard 由 `.github/workflows/scorecard.yml` 在 main 推送、每周计划和手动触发时运行，SARIF 上传到 Code Scanning。它只用于发现分支保护、Action 固定、依赖更新和安全响应等治理漂移：低分或单项发现不会直接阻断 PR，扫描执行失败仍应修复，具体风险仍按可利用性和项目边界人工复核。

### 2026-10-07 Scorecard 基线处置

本轮复核 `main` 上 28 个开放 Code Scanning 告警，均来自 OpenSSF Scorecard，而不是 CodeQL 业务代码路径。处置口径如下：

- `PinnedDependencies` 21 项与 `TokenPermissions` 2 项：外部 Action、Docker Action、Docker 基础镜像和 CI 工具均改为不可变版本或哈希，并增加仓库守卫；是否关闭以变更合入后的下一次 Scorecard 扫描为准。
- `Vulnerabilities` 1 项：当前 Dependabot 开放漏洞为 0；该项同样等待 Scorecard 重新计算，不能依据本地盘点手工标记已修复。
- `Fuzzing` 1 项：新增每周 Jazzer 覆盖引导 fuzz，当前覆盖游标解析和日志单行边界。自定义 workflow 是否被 Scorecard 识别由扫描器判定；即使评分未变化，真实 fuzz 任务仍保留。
- `BranchProtection`、`CodeReview` 各 1 项：仓库为单维护者模式，保留 PR 和 required checks，不伪造双人审批或自我审批。它们是已知治理残余，不以降低扫描规则或批量 dismiss 方式清零。
- `CIIBestPractices` 1 项：属于外部认证状态，不是代码缺陷；待项目确有认证需求时单独办理。

已关闭的历史告警须通过 GitHub API 核对 `dismissed_reason` 与 `fixed_at`。`fixed` 表示后续分析不再发现对应问题；人工关闭只在误报或明确接受风险时使用，并保留理由。不得把“closed”数量直接等同于代码修复数量。

私密漏洞报告入口和披露边界以根 [`SECURITY.md`](../../SECURITY.md) 为准。发现真实密钥时先撤销/轮换，再清理历史和修复注入路径。

## 报告位置

默认所有报告都统一写到仓库根目录下的 `target/security-scan-report/`：

- `gitleaks.json`
- `dependency-check/`
- `semgrep.json`
- `trivy-fs.json`
- `trivy-image.json`
- `zap-report.html`

改路径统一通过 `--report-dir=...` 控制。`zap-report` 也会自动跟到该目录下,除非显式覆盖。

## 前置条件

当前仓库根目录下默认可直接运行的是 `mvn`。其余工具需要先安装：

```bash
brew install gitleaks semgrep trivy
```

已安装过的,可先用下面命令确认：

```bash
command -v gitleaks semgrep trivy docker mvn
```

`docker` 需要本机 Docker Desktop 或等效容器运行时。

## 运行顺序

建议按下面顺序执行，先扫最容易误提交和最容易出供应链问题的部分，再做动态探测。

### 1. Secret 扫描

优先检查仓库里是否误放了 token、密钥、密码、私有证书等敏感信息。

安装：

```bash
brew install gitleaks
```

```bash
gitleaks detect --source . --redact --no-banner
```

补充说明：

- GitHub 公开仓库还可以启用 secret scanning / push protection，作为托底防线。
- `.env.local`、`.env.test`、`.env.prod` 已经应放入 `.gitignore`，不要提交真实密钥。

### 2. 依赖漏洞扫描

先扫 Maven 依赖树里的 CVE。

安装：

```bash
# Maven 已在当前环境可用；本机若没有，请先安装 Maven 3.9+
```

```bash
mvn -P compliance org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
```

关注点：

- 第三方依赖里是否引入了已知高危漏洞
- 生成的报告是否有可接受的例外项

### 3. 代码级 SAST

用 Semgrep 扫 Java / Spring 代码中的常见安全模式问题。

安装：

```bash
brew install semgrep
```

```bash
semgrep scan --config auto .
```

关注点：

- 输入校验缺失
- 不安全的字符串拼接
- SSRF / 路径穿越 / 命令执行风险
- 敏感信息回显

### 4. 文件系统与配置扫描

用 Trivy 扫源码目录、配置文件和潜在的 secret / misconfig。

安装：

```bash
brew install trivy
```

```bash
trivy fs . --scanners vuln,secret,misconfig
```

关注点：

- 仓库里的本地配置、脚本、模板文件
- Dockerfile / compose / YAML 里的错误配置
- 误放的二进制、jar、日志、转储文件

### 5. 应用镜像扫描

先构建应用镜像，再扫镜像层里的漏洞和敏感内容。

安装：

```bash
brew install trivy
```

```bash
docker build -f deploy/docker/Dockerfile.app --build-arg MODULE=batch-console-api -t batch-console-api:local .
trivy image batch-console-api:local
```

建议对这些镜像都各跑一次：

- `batch-console-api`
- `batch-orchestrator`
- `batch-trigger`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-process`
- `batch-worker-dispatch`

### 6. HTTP 动态探测

先起本地服务，再用 OWASP ZAP 做 baseline 扫描。

安装：

```bash
docker pull ghcr.io/zaproxy/zaproxy:stable
```

生产 / staging 验收必须带认证态，避免只扫到 401 / health 页面：

```bash
export BATCH_DAST_AUTH_HEADER_NAME=Cookie
export BATCH_DAST_AUTH_HEADER_VALUE='batch_console_token=<staging-jwt-cookie>'
bash scripts/ci/security-scan.sh -- \
  --mode=dast \
  --zap-scan=full \
  --require-zap-auth \
  --target-url=http://localhost:18080
```

`--require-zap-auth` 会在认证头缺失时直接失败，防止无认证 baseline 被误当作 Console API 安全门禁。

如需按 OpenAPI 合约做 application-level API 扫描，可改用 API scan：

```bash
bash scripts/ci/security-scan.sh -- \
  --mode=dast \
  --zap-scan=api \
  --zap-api-spec=docs/api/console-api.openapi.yaml \
  --require-zap-auth
```

## 推荐阈值

- `gitleaks`：零告警
- `dependency-check`：高危 CVE 为 0；中危需要人工评估
- `semgrep`：阻断高危规则；中低危按实际代码上下文复核
- `trivy`：镜像里不应出现高危漏洞和误放的 secret
- `ZAP baseline`：零高危；中危按接口暴露面处理

## 公开仓库建议

- 在 GitHub 启用 secret scanning alerts
- 在 GitHub 启用 push protection
- 保持 `LICENSE`、`NOTICE`、`CONTRIBUTING.md`、`SECURITY.md`、`CHANGELOG.md` 这些治理文件齐全
