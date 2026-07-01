# 可观测性文档索引

这里收纳批量调度系统的指标、日志、告警和 OpenTelemetry 相关文档与配置。

## 目录分工

- `otel-integration.md`：OpenTelemetry 接入说明和本地启动方式
- `otel-collector.yml`：Collector pipeline 示例配置
- `prometheus.yml`：Prometheus 抓取配置
- `prometheus-batch-rules.yml`：Prometheus 告警规则
- `alertmanager-batch-template.yml`：Alertmanager 路由和接收器模板
- `prometheus-grafana-baseline.md`：Prometheus / Grafana 基线说明
- `structured-logging-pipeline.md`：结构化日志管道示例
- `grafana-dashboard-batch.json`：Grafana 仪表盘模板
- `prometheus.yml`：Prometheus 抓取与规则配置
- `grafana-provisioning/`：Grafana 数据源自动注入配置
- [../deployment/observability-docker/](../deployment/observability-docker/)：独立的观测栈 Docker 环境说明

## 推荐阅读顺序

1. 先看 [otel-integration.md](./otel-integration.md)
2. 再看 [prometheus-grafana-baseline.md](./prometheus-grafana-baseline.md)
3. 然后看 [prometheus-batch-rules.yml](./prometheus-batch-rules.yml) 和 [alertmanager-batch-template.yml](./alertmanager-batch-template.yml)
4. 最后按需查看 [structured-logging-pipeline.md](./structured-logging-pipeline.md)

## 相关入口

- [docs/testing/README.md](../testing/README.md)
- [helm/README.md](../../helm/README.md)
- [helm/batch-platform/README.md](../../helm/batch-platform/README.md)

本目录的 `prometheus-batch-rules.yml` / `alertmanager-batch-template.yml` 是本地观测栈模板;`../../helm/batch-platform/files/prometheus-batch-rules.yml` 是 Helm 发布侧同步规则模板。
