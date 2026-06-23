package io.github.pinpols.batch.sdk.task;

import java.util.Map;

/**
 * 单个 task 的进度 / checkpoint 上报槽 — SDK Phase 4 / SDK-P4-2。
 *
 * <p>由 {@code TaskDispatcher} 每个 task 执行期创建并注册;handler 在长循环里调 {@link
 * SdkTaskContext#reportProgress(Map)} 写入最新快照,{@code LeaseRenewalScheduler} 下一次续租 tick 把 {@link
 * #latest()} 作为 renew 请求体的 {@code details} 捎给平台(落 job_task 供 console 任务详情读取)。
 *
 * <p>「最新值覆盖」语义:只保留最近一次快照,续租周期(默认远低于业务循环步长)自然采样,不积压。
 *
 * <p><b>敏感凭据禁入 details</b>(DB 密码 / OAuth secret 走环境变量,roadmap §5.5)—— details 会写入数据库并对运维可见。
 *
 * <p>{@code volatile} 保证执行线程的写对续约线程立即可见。
 */
public final class ProgressReporter {

  private volatile Map<String, Object> snapshot;

  /** 写入最新进度快照(不可变拷贝);传 null 清空。details 不得含 null 键 / 值。 */
  public void report(Map<String, Object> details) {
    this.snapshot = details == null ? null : Map.copyOf(details);
  }

  /** 最近一次快照;从未上报时返回 null。 */
  public Map<String, Object> latest() {
    return snapshot;
  }
}
