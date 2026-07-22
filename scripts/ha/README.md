# 高可用演练脚本

这里放 Kubernetes / 数据库高可用相关的阶段化演练入口。

- `bootstrap-secrets.sh`：初始化演练所需 secret。
- `install-operators.sh`：安装 HA 相关 operator。
- `apply-stage123.sh`：顺序应用 HA 演练前三阶段资源。
- `failover-drill.sh`：执行故障切换演练。

执行前先确认目标 kube context、namespace 和凭据，避免误操作生产环境。
