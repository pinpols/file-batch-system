# 季度依赖集中治理

## 目标

依赖更新采用“持续告警、季度集中实施、紧急漏洞例外”的模式。仓库不允许 Dependabot 自动创建版本或安全修复 PR，避免单个 patch 触发重复 CI、SBOM 漂移和未经验证的组合升级。

## 自动化边界

- `.github/dependabot.yml` 保留生态和忽略规则，但 `open-pull-requests-limit: 0`，不会创建版本更新 PR。
- 仓库级 Dependabot security updates 关闭自动修复 PR；Dependabot alerts、Dependency Graph、CodeQL、Trivy 和 Scorecard 继续提供发现证据。
- `quarterly-dependency-review.yml` 在每年 1、4、7、10 月首日生成 Maven、TypeScript、Python、Rust 和安全告警盘点，上传 90 天 artifact，并创建一个季度治理 Issue。
- 工作流只读依赖和生成报告，不改分支、不提交代码、不创建 PR。

## 实施流程

1. 从最新 `main` 建立一个 `chore/dependency-review-YYYY-qN` 分支。
2. 阅读季度 Issue 和 artifact，按“可利用安全漏洞 → 运行时 patch → 构建/测试工具 patch → minor → major”排序。
3. 一次只形成一张人工 PR。语义跨度过大的 major、JDK/Spring Boot 基线或数据库主版本升级另立决策，不强行捆绑。
4. Maven、Actions、Python、Node、Rust 和 Docker 必须使用各自锁定机制；Docker 使用可读 tag 加不可变 digest，外部 Action 使用 40 位提交 SHA，Node 包由 `package-lock.json` 的 integrity 锁定，Python CI 工具由 `uv.lock` 的哈希锁定，Rust crate 由 `Cargo.lock` 和 crates.io checksum 锁定。
5. Node/Rust 需区分兼容范围与执行基线：兼容矩阵继续覆盖 Node 22/24 和 Rust core MSRV 1.75，日常发布基线分别由 `.node-version`/`.nvmrc` 与 `rust-toolchain.toml` 固定到精确 patch，季度治理时统一提升。
6. Maven 依赖变化后重新生成 `docs/compliance/sbom.json`，运行许可证、漏洞、Action pin、workflow lint 和适用模块测试。
7. PR Gate 全绿后人工评审；合入后跟踪 Full Gate、CodeQL 和 Scorecard。只有后续完整扫描不再报告旧项，GitHub 才会自动将告警标为 fixed。

## 紧急安全例外

季度窗口不是漏洞响应 SLA。出现可达的 Critical/High 漏洞、被主动利用的供应链事件或凭据泄露时：

1. 立即建立单独安全修复 PR，不等待季度窗口；
2. 核实受影响版本、调用可达性和部署暴露面，不仅依据 CVE 标题升级；
3. 保留最小修复、定向回归、SBOM/许可证同步和回滚方案；
4. 在季度 Issue 中登记已提前处理的依赖，避免重复升级。

## 验收命令

```bash
python3 scripts/ci/check-github-action-pinning.py
bash scripts/ci/check-sbom-sync.sh
python3 scripts/ci/check-changelog-sync.py
bash scripts/ci/check-shell-scripts.sh
```

Docker digest、仓库安全开关、ruleset 和告警关闭状态必须通过 GitHub API 或实际镜像构建核验；文档声明不作为生效证据。

## 安全扫描配套

- `.github/workflows/fuzzing.yml` 每周对用户可控游标和日志字段运行受限时长的 Jazzer 覆盖引导 fuzz；`fuzz` 测试组默认不进入普通 Maven 单测，避免在 PR 测试 JVM 中加载 Jazzer hook。
- Fuzzing、CodeQL、Dependabot alerts 和 Scorecard 持续运行，不随季度依赖 PR 节奏暂停。季度集中的是升级实施，不是风险发现。
- Jazzer 运行生成的 `.cifuzz-corpus/` 是本地/CI 产物，不直接入库；确认能稳定复现真实缺陷的输入应转成具名回归测试，再随修复提交。
