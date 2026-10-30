# 本地日志留存与归档验证（2026-10-24）

## 基线与交付

- 工作树：`feature/workflow-daily-integration`，HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8`；大量既有暂存/未暂存改动仍保留，本次文件未提交。
- 新增 `scripts/local/manage-logs.py`、`config/local-log-retention.json` 和临时目录回归；旧 `cleanup-disk.sh` 的日志选项委托新入口。
- 原文默认保存：运行/应用历史日志7天、验证/旧布局30天；归档包默认90天，每次最多256MiB源文件。具体保护规则和操作命令以[原运行手册](../runbook/local-log-layout.md)为准。
- 真实工作树只执行预览；未归档或删除现有日志，未安装计划任务，未启停服务。

## 已执行

| 命令/验证 | 结果 |
|---|---|
| `python3 -m unittest scripts/ci/tests/test_local_log_retention.py` | 16/16通过；使用独立临时目录，覆盖内容、失败和保护边界 |
| `python3 scripts/local/manage-logs.py` | 退出0；37项运行目录约153.20MiB、861项应用归档原文约54.36MiB；verification/legacy无候选。数量为本轮时点快照 |
| `bash scripts/local/cleanup-disk.sh --include-run-logs --include-app-logs --retention-days 7` | 退出0；默认预览，成功委托相同保护规则与候选选择 |
| 隔离临时Git工作树，调用真实 `manage-logs.py --root <临时目录> --apply --confirm-root <临时目录>` 两次 | 退出0；真实lsof占用盘点、tar原文及SHA-256核对、原文件归档后移除，第二次不新增重复包；测试目录退出时清理 |
| `bash -n scripts/local/cleanup-disk.sh`、`shellcheck -S warning scripts/local/cleanup-disk.sh` | 退出0 |
| `python3 scripts/ci/check-shell-linux-portability.py scripts/local/cleanup-disk.sh` | 退出0；静态可移植性检查，不代表已在Linux主机运行 |
| 文档结构、日期策略、脚本登记与本次文件 `git diff --check` | 通过 |

## 门禁边界

`python3 scripts/ci/check-destructive-ops-governance.py` 全仓退出1，首个错误为既有 `V299__enforce_job_monitoring_policy_tenant_reference.sql` 的约束移除未登记；还包含既有V300–V344部分迁移、SQL清理、logging和Spark脚本增量。未把它们计为本次通过，也未替其扩大基线。

本次仅登记 `manage-logs.py` 的4个受保护操作：临时包失败清理、已验证原文移除、空源目录回收、显式到期包删除；将 `cleanup-disk.sh` 移除两处直接日志删除后的文件操作计数由6收紧为4。更新原高风险操作手册，未改检查器。

未执行生产/共享环境归档、90天真实等待或长期调度；过期规则由临时夹具验证。没有对当前应用stdout做在线大小轮转，当前应用仍由启停脚本在重启时归档。受保护证据可以超过默认保留期，需要业务确认后移除引用或固定标记；脚本不能替代异地备份。
