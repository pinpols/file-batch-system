# security-scan

这是一个很薄的安全扫描编排模块，只负责调用现成工具，不重写扫描器。

## 定位

- `gitleaks`：secret 扫描
- `dependency-check`：Maven 依赖漏洞扫描
- `semgrep`：代码级 SAST
- `trivy`：filesystem / image 扫描
- `ZAP`：HTTP 动态扫描

## 报告位置

默认所有报告统一输出到仓库根目录下的 `target/security-scan-report/`：

- `gitleaks.json`
- `dependency-check/`
- `semgrep.json`
- `trivy-fs.json`
- `trivy-image.json`
- `zap-report.html`

如果需要，也可以通过 `--report-dir=...` 改成其他目录。

## 运行方式

推荐直接通过仓库脚本执行：

```bash
bash scripts/ci/security-scan.sh
```

如果你想直接调用 Java 模块，也可以先打包再运行：

在仓库根目录执行：

```bash
mvn -f security-scan/pom.xml package
java -jar security-scan/target/security-scan-1.0.0-SNAPSHOT.jar --mode=all --root=. --target-url=http://localhost:8080
```

如果从 `security-scan/` 目录运行：

```bash
mvn package
java -jar target/security-scan-1.0.0.jar --mode=all --root=.. --target-url=http://localhost:8080
```

## 常用参数

- `--mode=all|secret|deps|sast|filesystem|image|dast`
- `--root=.`：仓库根目录
- `--report-dir=target/security-scan-report`
- `--target-url=http://localhost:8080`
- `--image-name=batch-console-api:local`
- `--zap-report=target/zap-report.html`
- `--continue-on-error`
- `--dry-run`

## 说明

- 这个模块不在根 `pom.xml` 的 reactor 里，避免影响常规构建。
- 扫描本身仍由外部工具完成，这里只做命令编排和结果汇总。
