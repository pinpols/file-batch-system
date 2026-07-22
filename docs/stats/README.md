# 代码量统计说明

历史 `loc-YYYY-MM-DD.md` 多数使用物理行数（`cat | wc -l`）快照，适合看仓库体量趋势，但会被换行格式、注释、文档、迁移 SQL 和生成文件放大。

新的精简口径使用 [scripts/dev/lean-loc-report.py](../../scripts/dev/lean-loc-report.py)。注意：主流工具（Sonar `NCLOC`、cloc、scc、tokei）的默认结果通常仍是“物理非空/非注释行”，不是逻辑语句行；本口径专门用于回答“格式化换行带来的体量水分”。

- 只统计 git 跟踪文件。
- 排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration。
- 主指标为 `Lean logical LOC`，按语句、块、配置项或 SQL statement 近似计数，减少多行参数、链式调用和格式化换行带来的虚高。

复跑示例：

```bash
python3 scripts/dev/lean-loc-report.py --write docs/stats/loc-$(date +%F)-lean.md
```
