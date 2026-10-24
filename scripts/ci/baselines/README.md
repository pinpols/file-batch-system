# CI 历史基线

本目录保存门禁消费的机器可读预算，属于工具配置，不是文档报告、运行日志或测试输入。入口及检查用法见 [CI 脚本索引](../README.md)。移动文件时同步读取器、测试、工作流、本地钩子与文档；不得为通过检查扩张预算。

| 基线 | 用途与维护方式 |
|---|---|
| [sql-inline-baseline.tsv](sql-inline-baseline.tsv) | Shell/Python 内联 SQL 的历史预算；当前为零，新增路径默认零预算。只能随 SQL 解耦减少，迁移路径时仍比对目标分支的原预算 |
| [java-suppression-baseline.tsv](java-suppression-baseline.tsv) | 生产 Java/YAML 的已审查抑制次数；源码取消抑制后同步减少。新增规则或扩大次数必须先核对规约、原因和影响，不直接用全量生成覆盖评审 |
| [destructive-ops-baseline.json](destructive-ops-baseline.json) | 删除、清理、格式化和相关破坏性命令的路径/类别预算；必须先评审目标、确认机制与恢复方案，不能用 --write-baseline 吞掉未审查差异 |
