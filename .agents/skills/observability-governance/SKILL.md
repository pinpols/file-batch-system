---
name: observability-governance
description: 设计、修改或审查应用日志、MDC、指标、追踪、Collector和告警触发/解除/通知链路时使用。只读运行诊断用operations-analysis，告警发送和故障注入需按用户明确范围执行。
---

# 日志、指标与告警治理

## 责任边界

技术日志、业务审计、技术指标告警和业务事件通知是不同事实链。先读[通知与审计边界](../../../docs/architecture/notification-and-audit-boundary.md)、[观测栈](../../../docs/runbook/observability-stack.md)和[SLI目录](../../../docs/runbook/slo-sli-catalog.md)，具体方法见[观测链路核对](references/observability-methods.md)。保留既有采集与投递组件，不另造日志数据库或同义通知通道。

纯运行现象分析使用`operations-analysis`并保持目标环境只读；本技能负责实现/规则审查，不授权启动观测栈、发送通知、关闭告警或进行故障注入。用户明确授权时才执行相应验证。

## 编码与错误表达

- 编码用法以 [编码规约](../../../docs/coding-conventions.md) §5、§12 和 [日志架构](../../../docs/design/logging-architecture.md) 为准：核对 `@Slf4j`/具名 logger 例外、Throwable 堆栈与 `SwallowedExceptionLogger` 摘要的选择，以及生产 `System.out/err`/`printStackTrace` 与 CLI、benchmark、测试诊断的边界。
- 用户可见错误按 [i18n 规范](../../../docs/design/i18n.md) 追错误码、key/args、双语资源、持久化三元组和读时渲染；技术日志模板与用户展示语言分别核对。不得因中文注释要求机械翻译协议键、稳定日志标签或历史示例，旧文档中的字段注入示例以当前构造器注入约定为准。

## 工作方法

1. 从业务风险或故障现象反查生产注册点、字段、采样/聚合、采集、规则、路由、接收端和持久化投递记录；不要只确认仪表盘、YAML或配置表存在。
2. MDC使用当前可信字段，区分请求声明租户与认证后身份，异步/线程复用验证绑定和清除；外部trace/request值不能覆盖可信身份。异常按既有脱敏与分类工具处理，避免泄漏凭据或重复完整堆栈。
3. 指标限定稳定低基数标签；tenant/task/instance等业务明细按当前日志metadata和业务事实存储，不直接增加到Prometheus时序。业务Alertmanager事件标签与Prometheus指标标签分开审查，不能一概禁止前者的租户隔离字段。
4. 规则验证触发、持续、恢复和相似合法反例。区分无target、抓取失败、零值和无序列；计数器窗口过期不代表具体故障已修。确认静默、确认、关闭和恢复通知各自语义。
5. 检查采集队列上限、重试、磁盘和拒收信号；日志降噪不能关闭根因。告警系统自身不可用应有独立可用性证据，不能只靠自己报告自己健康。
6. 使用仓库已有观测/日志/Helm同步守卫和适用规则工具；区分静态契约、规则fixture、运行采集和最终渠道送达。无真实接收端或未获发送授权时，不声明端到端交付通过。

## 结果

列明被改变的信号、业务含义、标签/脱敏、规则和部署副本、验证命令、触发/解除结果、实际收件与未验证项。业务状态、投递回执和日志分别对账；规则通过不替代业务验收。
