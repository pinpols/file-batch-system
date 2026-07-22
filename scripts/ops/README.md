# 运维巡检与自愈脚本

这里放本地和 staging 可复用的巡检、自愈和补偿入口。

## 常用入口

- `inspect-all.sh`：总巡检入口。
- `inspect-db.sh`：数据库健康、积压和 Flyway 状态巡检。
- `inspect-workers.sh`：worker 心跳、排空和任务占用巡检。
- `inspect-observability.sh`：观测栈连通性巡检。
- `trigger-compensation.sh`：触发补偿任务。

## 自愈脚本

- `heal-dead-letters.sh`
- `heal-drain-timeout.sh`
- `heal-retry-partitions.sh`
- `heal-retry-tasks.sh`
- `heal-stuck-outbox.sh`
- `heal-zombie-pipelines.sh`

`sql/` 保存巡检和自愈脚本调用的 SQL 片段，`testdata/` 保存 Alertmanager 配置生成器的样例。
