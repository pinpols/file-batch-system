package io.github.pinpols.batch.loadtest;

import static io.gatling.javaapi.core.CoreDsl.global;

import io.gatling.javaapi.core.Assertion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 压测公共配置入口,支持 Maven -D 参数和环境变量两级覆盖。所有 Gatling simulation 都从这里取参数。 */
public final class GatlingConfig {

    // ── 服务端点 ──────────────────────────────────────────────────────────────

    /** batch-trigger base URL。 */
    public static final String TRIGGER_BASE_URL =
            systemPropertyOrEnv("trigger.baseUrl", "TRIGGER_BASE_URL", "http://localhost:18081");

    /** batch-console-api base URL。 */
    public static final String CONSOLE_BASE_URL =
            systemPropertyOrEnv("console.baseUrl", "CONSOLE_BASE_URL", "http://localhost:18080");

    /**
     * batch-orchestrator 的 base URL,用于 {@code /internal/**} 探测；默认对齐本地
     * {@code BATCH_ORCHESTRATOR_PORT=18082}。
     */
    public static final String ORCHESTRATOR_BASE_URL =
            systemPropertyOrEnv(
                    "orchestrator.baseUrl", "ORCHESTRATOR_BASE_URL", "http://localhost:18082");

    /** 本地 trigger 端点需要的内部共享密钥。 */
    public static final String INTERNAL_SECRET =
            systemPropertyOrEnv("internal.secret", "BATCH_INTERNAL_SECRET", "internal-secret");

    // ── Test data ──────────────────────────────────────────────────────────────

    /** 负载测试请求使用的租户 ID。 */
    public static final String TENANT_ID =
            System.getProperty("tenantId", "t1");

    /** 启动模拟必须显式提供顶层 Workflow 目标。 */
    public static String requiredWorkflowDefinitionId() {
        return requiredWorkflowDefinitionId("workflowDefinitionId");
    }

    /** 显式 Workflow 目标；延迟解析，因此只读模拟无需提供 ID。 */
    public static String requiredWorkflowDefinitionId(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + propertyName + " is required for launch simulations");
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new IllegalArgumentException("-D" + propertyName + " must be positive");
            }
            return Long.toString(id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + propertyName + " must be a positive integer", exception);
        }
    }

    /** Business date for job launch requests (ISO-8601). */
    public static final String BIZ_DATE =
            System.getProperty("bizDate", "2026-01-15");

    /**
     * 压测运行标识。入口请求标识和追踪标识都必须包含该值，以便异步 Trigger 链路能被精确
     * 回收，不能仅依赖最终落到 job_instance 的参数快照。
     */
    public static final String RUN_ID =
            systemPropertyOrEnv("load.runId", "RUN_ID", "load-unknown");

    /**
     * JSON object inserted as the launch {@code params}. Prefer {@code launch.paramsJsonFile} for
     * 大体积导入载荷，避免超过命令行长度限制。
     */
    public static final String LAUNCH_PARAMS_JSON = resolveLaunchParamsJson();

    // ── Load profile ───────────────────────────────────────────────────────────

    /** Peak concurrent virtual users. */
    public static final int USERS_PEAK =
            Integer.parseInt(System.getProperty("users.peak", "20"));

    /** 升压阶段结束后的稳定运行时长，单位为秒。 */
    public static final int DURATION_SECONDS =
            Integer.parseInt(System.getProperty("duration.seconds", "120"));

    /** Ramp-up duration in seconds. */
    public static final int RAMP_SECONDS =
            Integer.parseInt(System.getProperty("ramp.seconds", "30"));

    /**
     * Max polls after launch in {@code LaunchPipelineCompletionSimulation} (each poll separated by
     * {@link #PIPELINE_POLL_INTERVAL_SEC}).
     */
    public static final int PIPELINE_MAX_POLLS =
            Integer.parseInt(System.getProperty("pipeline.maxPolls", "90"));

    /** 轮询实例状态之间的等待秒数。 */
    public static final int PIPELINE_POLL_INTERVAL_SEC =
            Integer.parseInt(System.getProperty("pipeline.pollIntervalSec", "2"));

    /**
     * Concurrent virtual users for {@code LaunchPipelineCompletionSimulation} (each holds long poll
     * loops; keep lower than generic {@link #USERS_PEAK}).
     */
    public static final int PIPELINE_COMPLETION_USERS =
            Integer.parseInt(System.getProperty("pipeline.completion.users", "10"));

    /** 调度或积压压力场景使用的固定写入速率。 */
    public static final double SCHEDULING_LAUNCH_RPS =
            Double.parseDouble(System.getProperty("scheduling.launch.rps", "5.0"));

    /** 调度压力场景使用的固定调度器或积压读取速率。 */
    public static final double SCHEDULING_READ_RPS =
            Double.parseDouble(System.getProperty("scheduling.read.rps", "5.0"));

    /** 调度或积压压力场景是否同时调用 Console 读取接口。 */
    public static final boolean SCHEDULING_CONSOLE_READS_ENABLED =
            Boolean.parseBoolean(System.getProperty("scheduling.console.reads", "false"));

    /** 模拟 Worker 生命周期压力使用的 CSV 文件，字段为 taskId、tenantId、workerId。 */
    public static final String TASK_LIFECYCLE_CSV =
            System.getProperty("task.lifecycle.csv", "target/task-lifecycle-tasks.csv");

    /** 模拟 Worker 生命周期压力中，成功认领与回报之间可选的暂停时长。 */
    public static final int TASK_LIFECYCLE_EXECUTE_PAUSE_MS =
            Integer.parseInt(System.getProperty("task.lifecycle.executePauseMs", "0"));

    // ── SLO thresholds ────────────────────────────────────────────────────────

    /** 写操作允许的最大 p95 响应时间（毫秒）。 */
    public static final int WRITE_P95_MS =
            Integer.parseInt(System.getProperty("slo.write.p95ms", "500"));

    /** 读操作允许的最大 p99 响应时间（毫秒）。 */
    public static final int READ_P99_MS =
            Integer.parseInt(System.getProperty("slo.read.p99ms", "300"));

    /** Maximum acceptable error rate percentage (0–100). */
    public static final double MAX_ERROR_RATE_PCT =
            Double.parseDouble(System.getProperty("slo.maxErrorPct", "1.0"));

    /** 构建断言，以支持错误数必须严格为零的要求。 */
    public static Assertion maxErrorRateAssertion() {
        if (MAX_ERROR_RATE_PCT <= 0.0d) {
            return global().failedRequests().count().is(0L);
        }
        return global().failedRequests().percent().lt(MAX_ERROR_RATE_PCT);
    }

    private GatlingConfig() {
    }

    private static String systemPropertyOrEnv(
            String propertyName, String envName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return defaultValue;
    }

    private static String resolveLaunchParamsJson() {
        String file = System.getProperty("launch.paramsJsonFile");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read launch.paramsJsonFile=" + file, e);
            }
        }
        return System.getProperty("launch.paramsJson", "{}");
    }
}
