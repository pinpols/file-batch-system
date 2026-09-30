# 可观测性脚本

本目录用于采集运行中的后端、Prometheus 和 OpenTelemetry 端点证据，不负责启动、停止或重启服务。

## 入口

- `capture-runtime-evidence.sh`：按环境变量配置的端点采集健康状态、指标和 trace 证据。

## 约定

- 默认使用宿主机可访问的 HTTP 端点；通过 `BATCH_*_URL` 环境变量覆盖地址。
- 脚本只读，不修改数据库、消息队列或对象存储。
- 采集结果目录通过 `OUTPUT_DIR` 指定，默认写入临时目录。
