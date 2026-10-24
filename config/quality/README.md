# 质量规则

本目录存放质量检查的受版本控制输入；构建报告、缓存及运行 JAR 放在被忽略的 `build/`、各模块 `target/` 或对应报告目录。

| 文件 | 用途 / 消费方 |
|---|---|
| [pmd-ruleset.xml](pmd-ruleset.xml) | Java PMD 规则集；根 pom.xml 的 maven-pmd-plugin 与参数规则回归共用 |
| [spotbugs-npe-filter.xml](spotbugs-npe-filter.xml) | SpotBugs 空指针检查的纳入过滤器；根 pom.xml 的 spotbugs-maven-plugin 使用 |

根构建通过 `maven.multiModuleProjectDirectory` 定位这些文件，多模块调用保持同一事实来源。规则语义以 [编码约定](../../docs/coding-conventions.md)和 [CI 手册](../../scripts/ci/README.md)为准。参数边界回归：`python3 -m unittest scripts/ci/tests/test_pmd_parameter_policy.py`。

`config/quality/*.xml` 变更由统一 CI 范围探测器归入 `java/config/ci`；修改规则会触发 Java 静态质量检查，目录 README 只触发文档范围。

本次从 `build/` 迁移只改变路径，保留原规则内容；新增构建产物不得再依靠 `build/` 的版本控制白名单入库。
