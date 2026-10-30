# 高可用演练脚本

这里放 Kubernetes / 数据库高可用相关的阶段化演练入口。

- `bootstrap-secrets.sh`：初始化演练所需 secret。
- `install-operators.sh`：安装 HA 相关 operator。
- `apply-stage123.sh`：顺序应用 HA 演练前三阶段资源。
- `failover-drill.sh`：执行故障切换演练，会删除 PG/Kafka/Redis/MinIO Pod。必须显式确认当前 kube context 与固定 namespace 清单；拒绝生产/预发布 context。

```bash
bash scripts/ha/failover-drill.sh all \
  --confirm-context "$(kubectl config current-context)" \
  --confirm-namespaces batch-data,kafka,redis,minio
```

执行前先确认目标 kube context、namespace 和凭据，避免误操作生产环境。

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [apply-stage123.sh](apply-stage123.sh) | 且 deploy/ha/*.yaml 的占位符(STORAGE_CLASS 等)已改。幂等:kubectl apply。 |
| [bootstrap-secrets.sh](bootstrap-secrets.sh) | ② 凭据 secret 引导。在 apply CR 之前建好,避免弱默认(本仓 prod profile 会拦 minioadmin/弱口令)。 |
| [failover-drill.sh](failover-drill.sh) | ④ HA 故障演练(混沌):逐组件 kill leader / 滚动重启,断言自动恢复。 |
| [install-operators.sh](install-operators.sh) | ① 安装 4 个 HA operator(集群级,各一次)。配合 deploy/ha/*.yaml。 |
