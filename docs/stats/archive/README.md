# 历史代码量快照

本目录保存历史代码量统计，供趋势对比和审计追溯使用，不作为当前项目代码量基线。

## 文件范围

- `loc-2026-05-29.md` 至 `loc-2026-07-19.md`：旧物理行数口径。
- `loc-2026-07-22-lean.md` 至 `loc-2026-09-24-lean.md`：精简逻辑行数口径。

## 当前基线

当前基线位于上级目录：[loc-current-lean.md](../loc-current-lean.md)。

复跑命令：

```bash
python3 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

历史报告的统计口径、提交状态和排除目录以各报告头部说明为准，不将不同口径的物理行数与精简逻辑行数直接相加或横向比较。
