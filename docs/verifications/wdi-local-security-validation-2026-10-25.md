# Workflow 整合本地安全验证（2026-10-25）

## 范围与结论

工作树为 `fix/wdi-current`，HEAD `f3d5e400d`，包含既有未提交变更。本记录证明本地检查及已列整改，不代表远端 CI、生产安全或零漏洞。原 B199 镜像为 3507 个生产输入的检查点 `dfa91f5ca8e9f3159b889e28de3ea7600e94a6f81a260a3c711a707afe3ce45c`。B200 已另构建并扫描八个镜像，包含 Worker 批量回报及共享默认配置；构建上下文记录为 3518 个输入、指纹 `1b50368f7899fcf94ab480a0ffcf61968bc67fb77df770abf2f37ab7fdf707ca`。构建期间另一会话修改 Console 的 WorkflowExecutionPushService，因此保留前后指纹差异，不将八镜像称为整个持续变化工作树的最终冻结版本。B200 七个交付文件的 SHA 单独核对。

| 检查 | 实际结果 | 边界 |
|---|---|---|
| Gitleaks 8.30.1 当前可版本化文件 | 7102 文件快照，raw **4**，exit 1；均核实为审计 CSV 的 SHA-256 误报 | 未修改原 SHA、配置或增加白名单；不宣称 secret 命令退出 0，不扫描被 Git 忽略的本地环境配置或历史提交 |
| Semgrep 1.182.1 | 199 条公开规则、5490 目标、0 解析错误；raw **18**（9 ERROR、9 WARNING） | 已逐条溯源：源码注解 SpEL、受控 SQL 标识符、只 connect 探针、本地 XML 报告与操作者配置 URL；本轮未确认可利用输入路径，未添加 suppression，不宣称扫描零发现 |
| NVD dependency-check 13.0.0 | **未通过**：公开数据更新因未配置 API Key、无本地漏洞文档而失败 | 不能由其它扫描覆盖该 FAIL；现行 CI 的 Trivy 路径单独报告 |
| Python SDK 依赖 | aiohttp 3.14.3→3.14.4、certifi 2026.7.22→2026.10.19；37 包中仅两包版本变化，定向复扫 0 漏洞 | pyproject 同步安全下限；HTTP/testkit/契约 94 项及补跑真实 socket 矩阵 1 项通过，共 95 个不同用例；不是全 SDK／全部语言验收 |
| Trivy 0.74.0 IaC | 按既有 CI ignore 与 HIGH/CRITICAL 策略通过 | AI 计费 token／普通配置名误报沿用原有精确登记，未新增 ignore；LOW 健康检查建议保留原件 |
| B199 与追加 B200 各八个应用候选镜像 | 两批分别保存八次 raw 与八次离线 policy 转换，均退出 0；B200 每镜像 **0 HIGH、0 CRITICAL、0 Secret** | B200 每镜像仍有 63 MEDIUM／16 LOW；不等于零漏洞。八镜像未部署，真实 SIM 使用另行记录身份的本地 JAR |
| 认证 DAST | **已执行，WARN_NONZERO，策略 exit 2**；四条受保护 GET 路由真实认证后返回 200；匿名四条均 401。12 发现：4 Low Cookie 无 HttpOnly、4 Low 缺 SameSite、4 Informational 会话响应；无 HIGH | 使用 ZAP 2.17.0 固定 digest，仅四条只读路由的被动扫描；不代表主动攻击、全部路由或发布安全验收。XSRF-TOKEN 由前端读取，不能盲目加 HttpOnly；SameSite 留作明确的后续配置维护，不隐藏原告警 |

## 已修复与未扩大范围

构建前发现 `.dockerignore` 未排除本地 `.env`：先追加 root／嵌套 `.env`、`.env.*` 排除，仅保留明确 `.env.example`。真实 BuildKit 合成上下文验证 6 个敏感配置名被排除、7 个公开模板／必要源码保留；未读取或输出真实本地环境文件内容。

镜像首轮每个 raw 有 2 个 HIGH，实际是同一 [CVE-2026-84782](https://ubuntu.com/security/CVE-2026-84782) 在 libssl3／openssl 两包上的记录。已先核实 Ubuntu Jammy 修复版及 APT 模拟，再仅将 Dockerfile 安装列表增加两包：`3.0.2-0ubuntu1.29`→`3.0.2-0ubuntu1.30`，0 新装、0 移除，不做全 OS 升级。8 镜像共同运行层受影响，故共享一次重建并复扫；基础 digest、Java／业务源码和 ignore 未因该修复改变。全部 8 包清单已核实修复版本。

63 MEDIUM／16 LOW 去重后为 **79 个 package/advisory 对**，逐项来源、上游 fixed 版本和组件范围见[非阻断依赖清单](../audit/wdi-image-dependency-residuals-2026-10-25.csv)。其中 36 个 MEDIUM 对有上游修复、27 MEDIUM／16 LOW 暂无 FixedVersion；按当前收尾范围交后续依赖维护，不计作已修复或新豁免。OGNL 检测位于 MyBatis 内嵌重定位代码，后续须评估 MyBatis 本体，不能用顶层依赖覆盖冒充修复。

## 执行与证据

- 入口：`gitleaks detect --no-git --source <可版本化文件快照> --config .gitleaks.toml --redact ...`；`semgrep scan --config p/security-audit --metrics=off --json ...`。
- 镜像：标准 `docker buildx bake -f docker-bake.hcl --load ...`，精确选择 8 应用 target、标签 `wdi-b199`。构建前后逐文件 SHA 相同，`:dev` 的 8 个原镜像 ID 均未变；未推送或部署。Console／Orchestrator 中间局部替换和首次构建身份均保留。
- 扫描：按不可变 image ID 各执行一次 `trivy image --scanners vuln,secret --ignorefile /dev/null --format json ...` 保存全 severity raw；再以同一 raw 执行 `trivy convert --ignorefile .trivyignore --severity CRITICAL,HIGH --exit-code 1 ...`，不为转换重复扫描。
- 原件清单：`gitleaks-final-versionable.json`、`secret-final-triage.json`、`security-reports/semgrep-security-audit.json`、`sast-triage.csv`、`security-deps-network.log`、`images-b199/openssl-rebuild/build-result.json`、`images-b199/scans-openssl-fixed/scan-results.json`、8 份 raw/policy、`openssl-installed-versions.json`。主会话按本批日志位置统一归档。
- 首轮因磁盘耗尽导致 Java 索引下载失败及 Docker 只读的原件保留，未记通过。经资源清理、Docker 恢复及无网络写探针通过后，以 >10GiB 磁盘前置、单份当天 Java DB 和漏洞库完成上述复扫；未删除共享卷／`:dev`，没有把环境故障解释成产品漏洞。

### B200 追加证据

- 八个不可变 image ID、构建前后生产输入和扫描结果分别在本机 `images-b200/build-result.json`、`production-before.json`、`production-after.json`、`candidate-components.json`、`scans-final/scan-results.json`；八个 `:dev` 标签未变。首个磁盘前置失败保留执行记录；只清理本任务已保留报告的旧候选镜像和可重建工具依赖后续跑，没有降低余量阈值。
- DAST 使用 `ghcr.io/zaproxy/zaproxy@sha256:5ccfff9a411743ab2615136a2edd7416bec9ce41572fa94e935df38c14adb2b6`。独立 URL 校验保持完整查询参数；ZAP Context 只匹配四个精确 origin/path。历史中四条实际请求与五条工具内部临时父节点分别核对，没有扩大爬虫或主动扫描范围。工具退出 0 与告警策略退出 2 分开记录。
- 原件在 `/private/tmp/wdi-script-security-1hhd7_6y/dast-prepared/`：`authenticated-dast-result.json`、认证/匿名覆盖清单、脱敏告警及三次工具范围校验失败。凭据文件权限 600，报告不含认证头；工具容器及本任务 ZAP 镜像已清理。运行环境的正常服务保留。
- NVD 检查仍未通过；四条秘密扫描误报、十八条 SAST 原始发现和八镜像的中低危残留保持原始数量，不能据本节写成所有安全工具通过。
