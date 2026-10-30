# 观测链路核对方法

## 日志、MDC与采集

沿应用stdout/OTLP→Collector→Loki/Tempo→查询核对字段和失败行为，检查敏感数据在应用与采集端的拦截。tenantId来自验证后身份，高基数字段使用既有structured metadata；业务审计保留目标租户、操作者和持久化关联，不以技术日志代替。

对重复告警/异常分别检查根因频次、重试放大和日志限流；吞异常、降低级别或抑制全部WARN不能作为根因修复。Collector的拒收、队列满、重启和后端不可达分别留证；停止组件做实验不是只读分析。

## 指标与业务口径

核对meter→Prometheus归一化名称→rule表达式→dashboard及Runbook。Counter/gauge语义、终态/失败口径、dry-run是否纳入、缺上下文如何计数必须一致。配置里有抓取地址不证明target被发现，无`up`序列也不等于健康；可缩零Worker与固定控制面target使用不同可用性判定。

## 告警与业务事件生命周期

按规则类型验证：持续故障保持firing、真正恢复后resolved、同组仍有活动事件时不能提前解除最后一组告警。`increase(counter[window])`自然过期另作反例；它不是某个任务的恢复证据。分组/抑制/静默保留租户边界；确认/静默/关闭不混用。

技术规则、业务alert_event、Workflow/Job终态通知及其回执有不同拥有权和去重单位。数据库路由有CRUD或生成器能渲染，不代表变更已加载到运行Alertmanager；查模板/渲染/重载及实际配置。通知结果未知先对账，不按超时盲发。

## 验证入口与证据

按实际变更选择`check-observability-contract.py`、`check-helm-prometheusrule-sync.sh`、`check-log-lifecycle.sh`、规则lint/fixture及定向测试；不要求每次全跑。静态同步、promtool规则测试、运行采集、通知最终接收分层报告。工具未安装、收件端未就绪或演练未授权分别列为未验证。

本地日志留存使用`manage-logs.py`及现有策略；预览与apply/恢复、软链接目标、活动文件和证据保留分别核对，不能用“日志清理”扩大到其他工作树或业务数据。

## 当前来源

- [日志架构](../../../../docs/design/logging-architecture.md)
- [观测栈](../../../../docs/runbook/observability-stack.md)
- [SLI与告警](../../../../docs/runbook/slo-sli-catalog.md)
- [通知与审计](../../../../docs/architecture/notification-and-audit-boundary.md)
- [日志布局](../../../../docs/runbook/local-log-layout.md)
- [观测守卫](../../../../scripts/ci/check-observability-contract.py)
- [日志留存工具](../../../../scripts/local/manage-logs.py)
- [证据采集](../../../../scripts/ops/capture-runtime-evidence.sh)
