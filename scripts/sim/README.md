# 多租户业务模拟器

模拟 ta / tb / tc 三个真实租户的 4 类 worker(IMPORT / EXPORT / DISPATCH / WORKFLOW)上下游交互,
本地端到端验证调度链路。

## 设计

| 租户 | 业务 | 4 类 worker job | 上游 source | 下游 sink |
|---|---|---|---|---|
| **ta** | 零售订单 (CUSTOMER / ORDER / SETTLE) | IMPORT × 2 / EXPORT / DISPATCH / WORKFLOW | SFTP /inbound | MinIO `ta/outbound/` + LOCAL archive |
| **tb** | 银行清算 (TRANSACTION / STATEMENT / RECONCILE) | IMPORT / EXPORT / DISPATCH / WORKFLOW | SFTP /inbound | MinIO `tb/outbound/` + MockServer `:1080/tb/*` |
| **tc** | 风控 (RISK_SCORE / RISK_ALERT / REVIEW) | IMPORT / EXPORT / DISPATCH / 3×WORKFLOW | SFTP /inbound | MockServer `:1080/tc/ingest` + SFTP |

租户配置(`job_definition` / `file_template_config` / `file_channel_config` / `workflow_*`)在
`docs/test-data/test-full-coverage-import-suite/{ta,tb,tc}-tenant-config-package-test.xlsx`,
**已经完整**,不用我重新写。

模拟器额外补的容器:
- `sftp`(atmoz/sftp):3 个账户 ta/tb/tc 共享同一容器,各 chroot 到 `/home/<user>/`
- `mockserver`(mockserver/mockserver):3 个 HTTP stub(`/tb/callback`, `/tb/ingest`, `/tc/ingest`)

## 文件清单

```
scripts/sim/
├── README.md                     # 本文档
├── compose.yml                   # 模拟器容器定义(sftp + mockserver)
├── mockserver-stubs/
│   └── expectations.json          # MockServer 启动期 stub 配置
├── 01-init-biz.sh                 # biz.* 表 + MinIO bucket
├── 02-start-sim.sh                # 起 sftp + mockserver
├── 03-import-tenants.sh           # 导入 ta/tb/tc Excel 配置到 console-api
├── 04-seed-source-data.sh         # 投放假业务 CSV 到 SFTP /inbound/
├── 05-load.sh                     # 中量循环触发 job(默认 5 轮 × 15 job)
├── 06-verify.sh                   # 对账 4 类 worker 产物
└── 99-stop.sh                     # 停模拟器容器(volume 保留)
```

## 跑通顺序(首次)

```bash
# 前置:主 compose 已 up,BE 全跑(make dev-start)+ make dev-health 全过

bash scripts/sim/01-init-biz.sh           # 一次性:biz.* 表 + MinIO bucket
bash scripts/sim/02-start-sim.sh          # 起 sftp + mockserver
bash scripts/sim/03-import-tenants.sh     # 一次性:导入 3 个租户配置
bash scripts/sim/04-seed-source-data.sh   # 每次跑:假数据投 SFTP
bash scripts/sim/05-load.sh               # 触发 75 个 job(5×15)
sleep 60                                  # 等 worker 跑完一轮
bash scripts/sim/06-verify.sh             # 对账产物
```

## 日常重跑(已 init 过)

```bash
bash scripts/sim/04-seed-source-data.sh   # 新一批源数据
bash scripts/sim/05-load.sh               # 触发
bash scripts/sim/06-verify.sh             # 看产物
```

## 调试入口

```bash
# SFTP 内容
docker exec sftp ls -la /home/ta/inbound/ /home/tb/inbound/ /home/tc/inbound/

# MockServer 收到的所有请求
curl -X PUT http://localhost:11080/mockserver/retrieve?type=REQUESTS

# MinIO Web 控制台
open http://localhost:19001    # 账号:minioadmin / minioadmin123

# 业务库
docker exec -it batch-postgres-primary psql -U batch_user -d batch_business
# \dt biz.*
# select tenant_id, count(*) from biz.customer_account group by tenant_id;

# job 实例状态
docker exec -it batch-postgres-primary psql -U batch_user -d batch_platform -c \
  "select tenant_id, job_code, status, count(*) from batch.job_instance \
   where create_time > now() - interval '15 min' group by 1,2,3 order by 1,2"
```

## 跟 load-tests/(Gatling)的关系

- `load-tests/`:**性能 SLO gate**(p95 / p99 + 错误率断言),CI 跑
- `scripts/sim/`:**日常 dev 联调**(看 4 类 worker 数据真落地),本地跑

两者用途不同,不重叠。
