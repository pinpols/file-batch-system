# 本地安全扫描 Runbook

这份清单用于在提交 GitHub 之前做一次本地自检，覆盖最常见的 secret 泄露、依赖漏洞、代码级 SAST、镜像与文件系统扫描，以及 HTTP 动态探测。

如果你想要一个统一入口，优先使用 [scripts/ci/security-scan.sh](../../scripts/ci/security-scan.sh)。
这个脚本会先打包 [security-scan/](../../security-scan/) 独立 Java 模块，再调用外部工具完成扫描；Java 模块只负责编排，不重写扫描器。

## 一键入口

在仓库根目录执行：

```bash
bash scripts/ci/security-scan.sh
```

如果你只想跑某一类，可以直接透传参数：

```bash
bash scripts/ci/security-scan.sh --mode=secret
bash scripts/ci/security-scan.sh --mode=deps
bash scripts/ci/security-scan.sh --mode=sast
bash scripts/ci/security-scan.sh --mode=filesystem
bash scripts/ci/security-scan.sh --mode=image
bash scripts/ci/security-scan.sh --mode=dast --target-url=http://localhost:18080
```

如果你必须跳过打包，也可以直接调用 Java 模块：

```bash
mvn -f security-scan/pom.xml package
java -jar security-scan/target/security-scan-1.0.0.jar --mode=all --root=. --target-url=http://localhost:18080
```

## 报告位置

默认所有报告都统一写到仓库根目录下的 `target/security-scan-report/`：

- `gitleaks.json`
- `dependency-check/`
- `semgrep.json`
- `trivy-fs.json`
- `trivy-image.json`
- `zap-report.html`

如果要改路径，统一通过 `--report-dir=...` 控制。`zap-report` 也会自动跟着改到这个目录下，除非你显式覆盖。

## 前置条件

当前仓库根目录下默认可直接运行的是 `mvn`。其余工具需要先安装：

```bash
brew install gitleaks semgrep trivy
```

如果你已经安装过，可以先用下面命令确认：

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
# Maven 已在当前环境可用；如果你本机没有，请先安装 Maven 3.9+
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
docker build -f docker/Dockerfile.app --build-arg MODULE=batch-console-api -t batch-console-api:local .
trivy image batch-console-api:local
```

建议对这些镜像都各跑一次：

- `batch-console-api`
- `batch-orchestrator`
- `batch-trigger`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-dispatch`

### 6. HTTP 动态探测

先起本地服务，再用 OWASP ZAP 做 baseline 扫描。

安装：

```bash
docker pull ghcr.io/zaproxy/zaproxy:stable
```

```bash
docker run --rm -t ghcr.io/zaproxy/zaproxy:stable \
  zap-baseline.py -t http://localhost:18080 -r zap-report.html
```

如果要扫更完整的控制台路径，建议先登录或配置上下文，再做 authenticated scan。

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
