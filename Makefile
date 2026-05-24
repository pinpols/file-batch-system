.PHONY: dev-build dev-start dev-stop dev-restart dev-restart-clean dev-restart-one
.PHONY: test test-unit test-it test-e2e test-all test-build test-parallel
.PHONY: data-system data-kafka data-minio
.PHONY: db-reset-flyway
.PHONY: ops-inspect ops-heal-stuck ops-heal-dead ops-heal-drain ops-heal-retry ops-heal-partitions ops-compensate ops-heal-zombie-pipelines
.PHONY: ci ci-pr ci-module
.PHONY: check-openapi check-deps-boundary check-version-alignment bump-version
.PHONY: pmd spotless spotless-fix coverage
.PHONY: scan-secret scan-deps scan-dast
.PHONY: apps-build observability-up observability-down observability-status observability-logs observability-inspect
.PHONY: help

## ── 本地开发环境 ──────────────────────────────────────────────────────────────

# 构建所有应用模块 jar（不启动进程）
dev-build:
	bash scripts/local/build-apps.sh

# 一键启动本地联调环境（Postgres / Kafka / MinIO / Redis + 全部 Java 进程）
dev-start:
	bash scripts/local/start-all.sh

# 停止所有本地 Java 进程
dev-stop:
	bash scripts/local/stop-all.sh

# 基建健康检查（PG primary/replica / Kafka / Redis / MinIO）— 协议层探测,
# env-var 驱动,任何环境可用。详见 scripts/local/health-check-infra.sh 顶部注释。
# CI / staging:覆盖 PG_PRIMARY_HOST / KAFKA_BOOTSTRAP / MINIO_URL 等环境变量。
dev-health:
	bash scripts/local/health-check-infra.sh

# 重新构建并启动（增量构建，推荐默认使用，~30s total）
# 增量模式覆盖 95% 日常改代码迭代：改 .java / .yml / 加类都能正确识别
dev-restart:
	bash scripts/local/stop-all.sh
	bash scripts/local/build-apps.sh
	bash scripts/local/start-all.sh

# 强制 clean 重启（~80s total）；遇到以下任一情况用这个兜底：
#   1) 删除 / 重命名 Java 类（旧 .class 可能残留在 target/classes 被打进 fat jar）
#   2) 删除 / 重命名 resource（yml / SQL / 静态文件同上风险）
#   3) 改了 parent pom 依赖版本，担心 .flattened-pom.xml 不同步
#   4) 新增 Flyway migration 脚本却报"脚本缺失"
#   5) 怀疑"幽灵 bug"、构建缓存状态不一致
dev-restart-clean:
	bash scripts/local/stop-all.sh
	CLEAN=1 bash scripts/local/build-apps.sh
	bash scripts/local/start-all.sh

# 重启单个或多个模块（不重建其他模块）
# 用法: make dev-restart-one M="trigger"
#       make dev-restart-one M="orchestrator trigger"
#       make dev-restart-one M="console" BUILD=1
dev-restart-one:
	bash scripts/local/restart.sh $(M)

## ── 测试 ──────────────────────────────────────────────────────────────────────

# 单元 + 集成测试（默认，跳过 E2E）
test:
	bash scripts/local/run-tests.sh

# 仅单元测试（秒级，无容器）
test-unit:
	bash scripts/local/run-tests.sh --unit

# 仅集成测试（需 Docker）
test-it:
	bash scripts/local/run-tests.sh --it

# E2E 测试
test-e2e:
	bash scripts/local/run-tests.sh --e2e

# 全量测试（单元 + 集成 + E2E）
test-all:
	bash scripts/local/run-tests.sh --all

# 仅构建（不跑测试），为 test-parallel 提供前置步骤
test-build:
	bash scripts/local/run-tests.sh --build-only

# 并行跑三类测试（先构建一次，再并发执行）
test-parallel: test-build
	bash scripts/local/run-tests.sh --unit --skip-build & \
	bash scripts/local/run-tests.sh --it   --skip-build & \
	bash scripts/local/run-tests.sh --e2e  --skip-build & \
	wait

## ── 测试数据 ──────────────────────────────────────────────────────────────────

# 导入系统测试数据
data-system:
	bash scripts/data/load-system-test-data.sh

# 初始化 Kafka topics
data-kafka:
	bash scripts/data/init-kafka-topics.sh

# 初始化 MinIO buckets
data-minio:
	bash scripts/data/init-minio.sh

## ── 数据库 ────────────────────────────────────────────────────────────────────

# 清空 Flyway 历史（重启 orchestrator/trigger 后重跑迁移）
db-reset-flyway:
	bash scripts/db/reset_platform_flyway_history.sh

## ── 运维巡检 / 修复 ───────────────────────────────────────────────────────────

# 全量巡检（DB + Kafka + 可观测性 + Worker）
ops-inspect:
	bash scripts/ops/inspect-all.sh

# 修复卡死的 outbox 事件
ops-heal-stuck:
	bash scripts/ops/heal-stuck-outbox.sh

# 修复死信队列
ops-heal-dead:
	bash scripts/ops/heal-dead-letters.sh

# 修复 drain 超时任务
ops-heal-drain:
	bash scripts/ops/heal-drain-timeout.sh

# 修复待重试任务
ops-heal-retry:
	bash scripts/ops/heal-retry-tasks.sh

# 修复分区不均衡
ops-heal-partitions:
	bash scripts/ops/heal-retry-partitions.sh

# 触发补偿
ops-compensate:
	bash scripts/ops/trigger-compensation.sh

# 转化超期 stale pipeline_instance(默认 7 天)为 FAILED 终态; 配合
# FileGovernanceProperties.processingDelayMaxAgeSeconds 收敛 latency scheduler 噪声
ops-heal-zombie-pipelines:
	bash scripts/ops/heal-zombie-pipelines.sh

## ── CI 回归 ──────────────────────────────────────────────────────────────────

# 全量回归（等同 full-ci-gate）
ci:
	bash scripts/ci/run-full-regression.sh

# PR 风格：跳过集成测试套件
ci-pr:
	bash scripts/ci/run-full-regression.sh --skip-it-suite

# 指定模块：make ci-module M=batch-console-api
ci-module:
	bash scripts/ci/run-full-regression.sh --skip-it-suite -- --pl $(M) -am -amd

## ── 静态检查 ──────────────────────────────────────────────────────────────────

# OpenAPI 路径与 Controller 对齐校验
check-openapi:
	python3 scripts/ci/check-console-openapi-paths.py

# 模块依赖边界校验
check-deps-boundary:
	python3 scripts/ci/check-dependency-boundaries.py

# 版本对齐校验（pom.xml <revision> ↔ Chart.yaml appVersion；4 个 .env 的 *_IMAGE_TAG 一致）
check-version-alignment:
	bash scripts/ci/check-version-alignment.sh

# 一条命令升级应用版本：同步 pom.xml <revision> 和 Chart.yaml appVersion
# 用法：make bump-version V=1.0.1
bump-version:
	bash scripts/ci/bump-version.sh $(V)

# PMD 代码规约（CLAUDE.md 规则）
pmd:
	mvn pmd:check -fae

# 格式检查（Google Java Format）
spotless:
	mvn spotless:check -fae

# 一键修复格式（提交前运行）
spotless-fix:
	mvn spotless:apply

# 覆盖率门禁（需先跑 make ci 或 mvn test）
coverage:
	mvn jacoco:check -fae

## ── 安全扫描 ──────────────────────────────────────────────────────────────────

# Secret 扫描
scan-secret:
	bash scripts/ci/security-scan.sh -- --mode=secret

# 依赖漏洞扫描
scan-deps:
	bash scripts/ci/security-scan.sh -- --mode=deps

# DAST 动态扫描（需本地服务已启动）
scan-dast:
	bash scripts/ci/security-scan.sh -- --mode=dast --target-url=http://localhost:8080

## ── 帮助 ──────────────────────────────────────────────────────────────────────

help:
	@grep -E '^[a-zA-Z_-]+:' Makefile | grep -v '^\.PHONY' | awk -F: '{print $$1}' | sort

## ── 应用构建 / 可观测性 ───────────────────────────────────────────────────────

apps-build:
	./scripts/docker/build-apps.sh

observability-up:
	./scripts/docker/up-observability.sh

observability-down:
	./scripts/docker/down-observability.sh

observability-status:
	./scripts/docker/observability/status.sh

observability-logs:
	./scripts/docker/observability/logs.sh

observability-inspect:
	./scripts/docker/observability/inspect.sh
