package io.github.pinpols.batch.orchestrator.observability;

import static io.github.pinpols.batch.common.observability.BatchMetricsNames.JOB_COMPLETION_TOTAL;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.JOB_DURATION_SECONDS;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.JOB_FAILURE_TOTAL;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.TAG_DRY_RUN;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.TAG_ERROR_CODE;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.TAG_JOB_TYPE;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.TAG_STATUS;
import static io.github.pinpols.batch.common.observability.BatchMetricsNames.TAG_TENANT;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * job_instance 生命周期指标:从 CREATED 走到终态(SUCCESS / FAILED / CANCELLED / TERMINATED)的时长 + 完成 / 失败计数。
 *
 * <p>缺失这组指标 = SLA / 错误率 / 长任务排查没数据。各调用方在 job_instance 状态切到终态时调本类的 record* 方法即可,本类不持任何状态。
 *
 * <p>tag 受控:tenant_id + job_type + status / error_code(error_code 仅 failure 路径)。**不打**
 * job_instance_id(高基数会爆 cardinality)。
 */
@Component
public class JobLifecycleMetrics {

  private final MeterRegistry registry;

  public JobLifecycleMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * job 走到终态时记录:end-to-end 时长 + 完成计数(按终态状态打 tag)。
   *
   * <p>ADR-026 dry-run:dryRun 维度统一打到 timer + counter,Grafana / 报警必须按 {@code dry_run="false"}
   * 过滤才能看到真实生产 SLA;{@code dry_run="true"} 单独看演练流量, 不污染真实生产 SLA / 错误率统计。
   */
  public void recordCompletion(
      String tenantId, String jobType, String terminalStatus, boolean dryRun, Duration duration) {
    Tags tags =
        Tags.of(
            TAG_TENANT,
            safe(tenantId),
            TAG_JOB_TYPE,
            safe(jobType),
            TAG_STATUS,
            safe(terminalStatus),
            TAG_DRY_RUN,
            Boolean.toString(dryRun));
    Timer.builder(JOB_DURATION_SECONDS)
        .tags(tags)
        .publishPercentileHistogram()
        .register(registry)
        .record(duration);
    Counter.builder(JOB_COMPLETION_TOTAL).tags(tags).register(registry).increment();
  }

  /**
   * 失败专用:除了完成计数,额外打 batch.orchestrator.job.failure.total 带 error_code,便于按错误码分桶报警。
   *
   * <p>同样带 dry_run tag,避免演练 instance 触发 FAILED 时拉爆生产错误率报警。
   */
  public void recordFailure(String tenantId, String jobType, String errorCode, boolean dryRun) {
    Tags tags =
        Tags.of(
            TAG_TENANT,
            safe(tenantId),
            TAG_JOB_TYPE,
            safe(jobType),
            TAG_ERROR_CODE,
            safe(errorCode),
            TAG_DRY_RUN,
            Boolean.toString(dryRun));
    Counter.builder(JOB_FAILURE_TOTAL).tags(tags).register(registry).increment();
  }

  /** 防 NPE / 空串变 "unknown",保持 Grafana 维度可枚举,不会因稀疏 null 拆出无效 series。 */
  private String safe(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
