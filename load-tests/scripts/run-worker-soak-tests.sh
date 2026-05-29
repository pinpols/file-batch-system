#!/usr/bin/env bash
# =========================================================
# run-worker-soak-tests.sh — 24h+ 持续压测(soak / longevity)
#
# 现状:仓里只有 run-worker-stress-tests.sh(短时 burst),不验长期影响。
# 缺口:连接池泄漏、Map<String,Counter> 静态缓存增长、跨日 bizDate 切换漂移、
#       Hikari connection leak、Kafka consumer rebalance 累计 lag 等只有跑得
#       够长才能暴露,本脚本补这一档。
#
# 设计:
#   - 持续 24h+(由 SOAK_HOURS 环境变量控,默认 24)
#   - 持续而非 burst:RPS 控制在生产 50%(约 200 TPS)
#   - 采 JFR(.jfr 文件)+ 周期性 heap dump 到 logs/soak/
#   - 收尾用 jfr summary + jcmd GC 看长期趋势
#
# 待补:
#   - JFR 启动参数注入(-XX:StartFlightRecording=...)
#   - 周期 heap dump(jcmd $pid GC.heap_dump)
#   - 跨日触发:hack JVM 时钟前进 + 验 batch_day_instance 正常翻日
#   - 退出条件:任意 health 指标超阈值立即 stop 留现场
#
# 用法:
#   SOAK_HOURS=24 bash load-tests/scripts/run-worker-soak-tests.sh
# =========================================================

echo "TODO: r3 validation-infra — 实现 soak harness。骨架已建,等填充。"
echo "参考:run-worker-stress-tests.sh 已有的压力源,扩展为长跑模式。"
exit 1
