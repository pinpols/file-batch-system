package com.example.batch.orchestrator.config;

import com.example.batch.common.constants.CommonConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件治理配置（{@code batch.file-governance}）。
 *
 * <p>编排端 5 类文件治理调度器的统一开关：
 *
 * <ul>
 *   <li><b>Latency</b>：到达 / 处理延迟监控
 *   <li><b>Archive</b>：文件记录归档
 *   <li><b>Reconcile</b>：MinIO 桶 vs DB file_record 对账
 *   <li><b>Arrival</b>：文件到达 SLA + 超时动作
 *   <li><b>Access</b>：预签名 URL 下载控制
 * </ul>
 *
 * 详见 design/file-pipeline-design.md + design/sla-and-quality.md。
 */
@Data
@ConfigurationProperties(prefix = "batch.file-governance")
public class FileGovernanceProperties {

  private final Latency latency = new Latency();
  private final Archive archive = new Archive();
  private final Reconcile reconcile = new Reconcile();
  private final Arrival arrival = new Arrival();
  private final Access access = new Access();
  private final UploadSession uploadSession = new UploadSession();

  /** 文件到达 / 处理延迟监控（写指标到 Prometheus，超过阈值告警）。 */
  @Data
  public static class Latency {
    /** 监控总开关。关闭后 LatencyScheduler 不启动。 */
    private boolean enabled = true;

    /** 扫描间隔（ms）。短 → 指标更新及时，DB 压力大。 */
    private long pollIntervalMillis = 30000L;

    /** 到达延迟告警阈值（秒）。文件未在期望时间到达超过该值 → WARN 指标。 */
    private long arrivalDelayThresholdSeconds = 600L;

    /** 处理延迟告警阈值（秒）。文件已到达但未完成处理超过该值 → WARN 指标。 */
    private long processingDelayThresholdSeconds = 900L;

    /**
     * 处理延迟"zombie"上限阈值（秒）。超过该值的 stale pipeline 视为 zombie（卡死/进程崩溃后未恢复/测试数据 残留），从延迟告警查询中排除,避免每 30s 反复
     * WARN 同一条早就 dead 的 pipeline 持续刷屏。zombie 应由独立的 dead-letter / sweep job 处理(置为 FAILED 终态)。默认 7 天
     * = 604800 秒。
     */
    private long processingDelayMaxAgeSeconds = 604800L;

    /**
     * RUNNING pipeline 自动终态化阈值（秒）。超过该时间仍未结束的 pipeline 视为 worker 崩溃 / 测试残留 / 不可恢复卡单，由治理扫描置为
     * FAILED，避免长期重复告警。默认 12 小时；生产若允许更长单任务，应显式调大。
     */
    private long staleRunningFailSeconds = 43200L;

    /** 单次 stale sweep 最多处理多少条，避免一次更新过多历史脏数据。 */
    private int staleSweepBatchSize = 100;

    /** 单次扫描采样数，平衡精度与开销。 */
    private int sampleSize = 20;
  }

  /** 文件记录归档（冷数据从主表迁到 archive 表）。 */
  @Data
  public static class Archive {
    /** 归档总开关。关闭后 ArchiveScheduler 不启动。 */
    private boolean enabled = true;

    /** 归档调度间隔（ms）。 */
    private long cleanupIntervalMillis = 60000L;

    /** 单次归档批大小（一次 SQL 处理多少条）。大批次锁表时间长。 */
    private int cleanupBatchSize = 100;

    /** 保留天数。超过该值的 file_record 被归档。 */
    private int retentionDays = 7;
  }

  /** 对象存储 vs DB 对账（找出"DB 有 OSS 没"或"OSS 有 DB 没"的孤儿）。 */
  @Data
  public static class Reconcile {
    /** 对账总开关。 */
    private boolean enabled = true;

    /** 对账调度间隔（ms）。 */
    private long pollIntervalMillis = 60000L;

    /** 单次对账批大小（一次扫描多少 OSS 对象）。 */
    private int batchSize = 200;

    /** 默认租户（多租户对账时未指定的兜底）。 */
    private String defaultTenantId = CommonConstants.DEFAULT_TENANT_ID;

    /** OSS 对象前缀（限定对账范围，空 = 全桶）。 */
    private String prefix = "";

    /** 是否纳入临时对象（{@code .tmp} / {@code .uploading} 等）。默认 false 避免误清理上传中文件。 */
    private boolean includeTemporaryObjects = false;
  }

  /** 文件到达 SLA + 超时动作（详见 design/sla-and-quality.md §2）。 */
  @Data
  public static class Arrival {
    /** 到达 SLA 总开关。 */
    private boolean enabled = true;

    /** 扫描间隔（ms）。 */
    private long pollIntervalMillis = 30000L;

    /** 单次扫描批大小。 */
    private int batchSize = 200;

    /**
     * 未配置 timeout action 时的默认动作。可选：{@code BLOCK_DOWNSTREAM} / {@code WAIT_MORE} / {@code
     * MANUAL_CONFIRM} / {@code SKIP_BATCH} / {@code EMPTY_RUN}。
     */
    private String defaultTimeoutAction = "MANUAL_CONFIRM";

    /** 文件齐全时是否自动触发下游。false → 仅打标，等人工 confirm。 */
    private boolean triggerOnComplete = true;

    /** {@code MANUAL_CONFIRM} 模式下"延长等待"按钮单次延长秒数。 */
    private long manualWaitExtensionSeconds = 1800L;
  }

  /**
   * 托管上传会话孤儿清理（#440 {@code createUploadSession} 创建占位 file_record 后, 前端既不上传也不调 confirmFileArrival
   * 时该行会永久滞留——到达组调度不处理、归档清理不清、对账不清）。 超过 TTL 且对象存储中确认无对象的占位行由清理任务置为 DELETED 终态。
   */
  @Data
  public static class UploadSession {
    /** 孤儿清理总开关。关闭后 UploadSessionCleanupScheduler 不执行。 */
    private boolean cleanupEnabled = true;

    /** 清理调度间隔（ms）。孤儿会话不紧急，默认 1 小时扫一次。 */
    private long cleanupIntervalMillis = 3600000L;

    /** 孤儿判定 TTL（秒）。创建超过该时长仍未上传 / 未确认的会话视为孤儿。默认 24 小时。 */
    private long orphanTtlSeconds = 86400L;

    /** 单次清理批大小。 */
    private int cleanupBatchSize = 100;
  }

  /** 预签名 URL 下载控制（避免暴露长期凭证）。 */
  @Data
  public static class Access {
    /** 预签名下载总开关。关闭后所有下载走应用代理。 */
    private boolean enabled = true;

    /** 预签名 URL 有效期（秒）。短 → 安全但用户体验差；建议 600 以内。 */
    private int presignExpirySeconds = 600;
  }
}
