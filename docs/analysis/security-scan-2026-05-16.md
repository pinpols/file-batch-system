# 全栈安全扫描报告 — 2026-05-16

> 6 类全套扫描结果，`bash scripts/ci/security-scan.sh -- --mode={secret,deps,sast,filesystem,image,dast}` 逐一执行（每类单跑、不连锁阻断）。本次扫描发生在 R7 OSS 漏洞修复（SB 4.0.6 升级 commit `9df1d1a8`）之后、日志审计修复（commit `8373de94`）之后。

## 执行汇总

| Mode | 工具 | 状态 | 耗时 | Finding 数 |
|---|---|---|---|---|
| **secret** | gitleaks 8.x | exit 1 (找到 leak) | 5s | **5** |
| **deps** | OWASP dependency-check 12.2.2 | exit 1 (CVSS≥7) | 63s | **2 HIGH**（OTel-semconv 假阳性） |
| **sast** | semgrep 1.136 (docker) | OK | 130s | **22**（3 ERROR + 19 WARNING） |
| **filesystem** | trivy fs 0.70 | OK | 28s | **162** vuln（70 HIGH + 2 CRITICAL）+ 1 misconf |
| **image** | docker build + trivy image | docker build FAIL | 49s | 历史报告 60 项（trivy-image.json 12:33）|
| **dast** | OWASP ZAP baseline (docker) | OK | 147s | **0 / 0 / 0 / 0**（无 alert） |

报告产物位置：
- `target/security-scan-report/{gitleaks,semgrep,trivy-fs,trivy-image}.json` + `zap-report.html`
- `target/dependency-check-report.{html,json,xml,sarif,csv}` + 各模块 target/

## 1. SECRET — gitleaks (5 leaks)

| 文件 | RuleID | 性质 |
|---|---|---|
| `batch-console-api/src/test/.../ConsoleJwtServiceTest.java:38` | generic-api-key | **测试 fixture**（JWT secret 字面量） |
| `batch-console-api/src/test/.../ConsoleHttpIntegrationTest.java:225` | generic-api-key | **测试 fixture** |
| `batch-console-api/src/test/.../ConsoleHttpIntegrationIT.java:146` | generic-api-key | **测试 fixture** |
| `docs/runbook/realtime-sse-verification.md:24` | curl-auth-header | **文档示例** curl 命令 |
| `batch-worker-import/src/test/resources/fixtures/test-rsa-private-pkcs8.pem` | private-key | **测试 fixture** RSA 私钥 |

**判定**：全 5 项为测试 fixture / 文档示例，**非真实泄漏**。处置：加 `.gitleaksignore` 白名单：
```
batch-console-api/src/test/java/com/example/batch/console/support/auth/ConsoleJwtServiceTest.java:generic-api-key:38
batch-console-api/src/test/java/com/example/batch/console/integration/ConsoleHttpIntegrationTest.java:generic-api-key:225
batch-console-api/src/test/java/com/example/batch/console/integration/ConsoleHttpIntegrationIT.java:generic-api-key:146
docs/runbook/realtime-sse-verification.md:curl-auth-header:24
batch-worker-import/src/test/resources/fixtures/test-rsa-private-pkcs8.pem:private-key:1
```

## 2. DEPS — OWASP dependency-check (2 HIGH 假阳性)

R7 SB 4.0.6 升级前命中 21 个 CVE，本次扫描剩 2 项全在 `batch-common` 模块：

| 组件 | CVE | CVSS | 实际影响 |
|---|---|---|---|
| `opentelemetry-semconv-1.37.0.jar` | CVE-2026-29181 | 7.5 | **OpenTelemetry-Go 专属漏洞**，CPE 模糊匹配命中 Java semconv，**假阳性** |
| `opentelemetry-semconv-1.37.0.jar` | CVE-2026-39883 | 7.0 | **同 Go-only**，**假阳性** |

**判定**：Java 的 `opentelemetry-semconv` jar 是纯数据 jar（不含 Go 代码路径），NVD CPE 配置 `cpe:2.3:a:opentelemetry:opentelemetry` 模糊匹配所有语言实现 → 已知误报模式。

**处置**：写 `dependency-check-suppressions.xml` 显式白名单：
```xml
<suppress>
  <notes>OpenTelemetry-Go specific CVEs; Java semconv jar is metadata-only with no code paths affected</notes>
  <packageUrl regex="true">^pkg:maven/io\.opentelemetry\.semconv/opentelemetry-semconv@.*$</packageUrl>
  <vulnerabilityName>CVE-2026-29181</vulnerabilityName>
  <vulnerabilityName>CVE-2026-39883</vulnerabilityName>
</suppress>
```

## 3. SAST — semgrep (22 findings)

### ERROR × 3（真实风险，需关注）

| 文件 | 规则 | 性质 |
|---|---|---|
| `security-scan/ProcessCommandExecutor.java:19` | `command-injection-process-builder` | **security-scan 模块自己**：ProcessBuilder + 格式化字符串拼命令 — 调用方都是受控（mvn / gitleaks / trivy 内部），但模式不好 |
| `scripts/ci/check-dependency-boundaries.py:13` | `use-defused-xml-parse` | Python `xml` lib 有 XXE 风险 — 仅扫 pom.xml（trusted 输入），**可豁免** |
| `batch-worker-import/.../test-rsa-private-pkcs8.pem:1` | `detected-private-key` | **测试 fixture 私钥**（与 secret 扫描重复）|

### WARNING × 19（重要 3 项）

| 文件 | 规则 | 处置 |
|---|---|---|
| 🔴 `ConsoleCacheInvalidationAspect.java:83` | **`spel-injection`** Spring 表达式注入 | **真风险**：审查动态 SpEL 构造，确保表达式片段不来自用户输入 |
| 🟡 `DefaultConsoleAiApplicationService.java:264` | `bad-hexa-conversion` | `Integer.toHexString` 丢前导 0 → 哈希弱化；改 `String.format("%02x", b)` |
| 🟡 `.github/workflows/staging-gate.yml:49` | `workflow-run-target-code-checkout` | GH Actions `workflow_run` checkout PR 代码模式（小概率 RCE）|

剩 16 项均为：`RemoteFilesystemDispatchSupport` unencrypted socket × 2（SFTP 实现层，必须）+ 7× shell `ifs-tampering`（脚本卫生）+ 其它低优先级。

## 4. FILESYSTEM — trivy fs (70 HIGH + 2 CRITICAL)

trivy 用 Aqua 数据库，与 OWASP 视角不同，捕获多个上游传递依赖问题：

### CRITICAL × 2 + HIGH × 70（按包聚合）

| 包 | 版本 | CVE 数 | Fix Version | 来源 |
|---|---|---|---|---|
| **io.netty:netty-codec-compression** | 4.2.12.Final | 1 | 4.2.13.Final | Spring Boot 默认 Netty |
| **io.netty:netty-codec-dns** | 4.2.12.Final | 1 | 4.2.13.Final | 同上 |
| **io.netty:netty-codec-http** | 4.2.12.Final | 2 | 4.2.13.Final | 同上 |
| **io.netty:netty-codec-http2** | 4.2.12.Final | 1 | 4.2.13.Final | 同上 |
| **io.netty:netty-codec-http3** | 4.2.12.Final | 1 | 4.2.13.Final | 同上 |
| **io.netty:netty-transport-native-epoll** | 4.2.12.Final | 1 | 4.2.13.Final | 同上 |
| **org.bouncycastle:bcprov-jdk18on** | 1.78.1 + 1.81 | 1 | 1.84 | minio / jose4j |
| **org.postgresql:postgresql** | 42.7.10 | 1 | 42.7.11 | spring-boot-starter-data-jdbc / 自管 |
| **org.bitbucket.b_c:jose4j** | 0.7.0 | 2 | 0.9.4 | minio 4.x 旧 dep |
| **org.asynchttpclient:async-http-client** | 2.10.4 | 1 (CVE-2024-53990) | 2.12.4 | minio 4.x 旧 dep |

**处置**：
- **Netty** 等待 Spring Boot 4.0.7 patch 升 4.2.13.Final，或 pom override
- **bouncycastle / postgresql** 直接 pom override 升 1.84 / 42.7.11
- **minio 拖来的 jose4j + async-http-client** — 升 minio 客户端版本（当前 8.x 应已弃用这些依赖）

### Misconfiguration × 1

| 文件 | 规则 | 详情 |
|---|---|---|
| `helm/batch-platform/templates/secret.yaml:18` | helm scanner 渲染失败 | `security.internalSecret must be provided` — 设计如此（必须 --set 强密钥），**非真 misconfig**，可加 `.trivyignore` |

## 5. IMAGE — trivy image (历史报告，60 项)

**本次 docker build 失败**（mvn install 阶段，可能是 R1/R2/R3 修改触发某 IT 测试通过但 spotless 影响），**沿用 12:33 历史 trivy-image.json**：

| Class | Count |
|---|---|
| HIGH | 9 |
| MEDIUM | 32 |
| LOW | 19 |

HIGH 项与 trivy fs 完全重叠（Netty 7 项 + bouncycastle + postgresql）。OS 层（ubuntu 22.04 jammy）未扫到 CRITICAL/HIGH。

## 6. DAST — ZAP baseline (0 alerts)

打的是 `http://host.docker.internal:18080`（本机 console-api 根路径）。

| Severity | Count |
|---|---|
| High | **0** |
| Medium | **0** |
| Low | **0** |
| Informational | **0** |
| False Positives | 0 |

**0 alert 的解读**：console-api 根路径要求认证（`/` 返回 401 + 不暴露任何 anonymous 内容），ZAP 被动 spider 没能 crawl 到任何受保护页面，无被动规则可触发。**这是设计如此的副作用，不代表"无漏洞"，只代表"ZAP baseline 没有 attack surface"**。

要做真 DAST 应该：
1. 给 ZAP 配 `replacer` 规则注入有效 JWT cookie → 让它能 crawl 已认证路径
2. 或起 active scan（baseline 是 passive only，不主动构造 payload）

当前 baseline 仅证明：**未认证 anonymous 表面没有头部漏洞 / 信息泄漏**（足够 SSRF / SQL-i / XSS 这种 active scan 项目它都没探）。

## 处置优先级

### P0 — 这周改完
1. **trivy fs HIGH** 11 个组件升级（pom 显式 override）
2. **`ConsoleCacheInvalidationAspect.java` SpEL injection** 审查
3. **`ProcessCommandExecutor.java` ProcessBuilder 拼接** 审查（虽是内部）

### P1 — 短期内闭环
4. `.gitleaksignore` 白名单 5 项测试 fixture
5. `dependency-check-suppressions.xml` 抑制 OTel 假阳性
6. `.trivyignore` 抑制 helm secret 必填 misconfig
7. **DAST 实质化**：给 ZAP 配 authenticated session 跑 active scan（当前 baseline 0 alert 是因为 ZAP 没拿到认证 token，crawl 不到任何已保护页面）
8. `DefaultConsoleAiApplicationService.java` bad-hexa 修

### P2 — 排期
9. minio 旧 dep 升级（连带 jose4j / async-http-client）
10. workflow-run-target-code-checkout 审查 GH Actions `staging-gate.yml`
11. shell `ifs-tampering` 7 处脚本卫生（影响很小）

## 关联文档

- `docs/runbook/security-scan.md` — 扫描工具使用指南
- `docs/analysis/audit-r6-r7-2026-05-15.md` — R6/R7 静态审计战役
- 本扫描产物：`target/security-scan-report/`、`target/dependency-check-report.*`
