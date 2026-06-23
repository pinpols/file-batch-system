package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.application.WorkerRuntimeFacade;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import jakarta.annotation.PreDestroy;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Worker 生命周期模板（所有 worker 通用骨架）。
 *
 * <p>使用方式：
 *
 * <ul>
 *   <li>子类只提供差异化配置：{@link #workerConfiguration()} / {@link #workerGroup()} / {@link #workerPort()}
 *   <li>子类用一个很薄的 {@code @Scheduled} 方法定期调用 {@link #doHeartbeat()}（避免在抽象类里硬编码配置 key）
 * </ul>
 *
 * <p>该模板负责：
 *
 * <ul>
 *   <li>应用启动后自动注册（{@link #onReady()}）
 *   <li>周期心跳（{@link #doHeartbeat()}）
 *   <li>优雅下线（{@link #onShutdown()}）
 * </ul>
 */
@Slf4j
public abstract class AbstractWorkerLoop {

  private final WorkerRuntimeFacade workerRuntimeFacade;
  private final BatchDateTimeSupport dateTimeSupport;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private volatile WorkerRegistration registration;

  @Value("${batch.worker.registry.fail-fast-on-startup:true}")
  private boolean failFastOnStartup;

  protected AbstractWorkerLoop(
      WorkerRuntimeFacade workerRuntimeFacade, BatchDateTimeSupport dateTimeSupport) {
    this.workerRuntimeFacade = workerRuntimeFacade;
    this.dateTimeSupport = dateTimeSupport;
  }

  /** Worker 配置（topic、tenantId、workerType 等）。 */
  protected abstract WorkerConfiguration workerConfiguration();

  /** worker 逻辑分组，如 {@code import}/{@code export}/{@code dispatch}。 */
  protected abstract String workerGroup();

  /** worker 对外端口（用于注册元数据；E2E 合并进程时通常为 orchestrator 端口）。 */
  protected abstract int workerPort();

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      ensureStarted();
    } catch (Exception ex) {
      if (failFastOnStartup) {
        throw ex;
      }
      log.warn(
          "{} worker register-on-startup failed; will retry on heartbeat. reason={}",
          workerGroup(),
          summarizeRegistrationFailure(ex));
    }
  }

  /**
   * 由子类的 {@code @Scheduled} 方法调用。
   *
   * <p>把 {@code @Scheduled} 放在子类，是为了避免在抽象层硬编码配置 key（各 worker 的心跳间隔配置可能不同）。
   */
  protected void doHeartbeat() {
    if (stopping.get()) {
      return;
    }
    WorkerRegistration current;
    try {
      current = ensureStarted();
    } catch (Exception ex) {
      log.warn(
          "{} worker start/register failed; will retry on next heartbeat. cause={}",
          workerGroup(),
          ex.getMessage());
      return;
    }
    try {
      if (stopping.get()) {
        return;
      }
      workerRuntimeFacade.heartbeat(current.getWorkerId());
    } catch (Exception ex) {
      log.warn(
          "{} worker heartbeat unavailable: {} ({})",
          workerGroup(),
          ex.getMessage(),
          ex.getClass().getSimpleName());
    }
  }

  /** 幂等启动：首次调用注册 worker，后续调用直接返回注册信息；双重检查加锁保证线程安全。 */
  public WorkerRegistration ensureStarted() {
    if (started.get()) {
      return registration;
    }
    synchronized (this) {
      if (started.get()) {
        return registration;
      }
      WorkerConfiguration cfg = workerConfiguration();
      WorkerRegistration workerRegistration = new WorkerRegistration();
      workerRegistration.setWorkerId(buildWorkerId(cfg));
      workerRegistration.setTenantId(cfg.tenantId());
      workerRegistration.setWorkerType(cfg.workerType());
      // 源头归一 workerGroup 为大写，避免 IMPORT / import 同语义字符串被 ResourceScheduler 等值比较误失配
      workerRegistration.setWorkerGroup(CodeNormalizer.toUpperOrNull(workerGroup()));
      workerRegistration.setHost(resolveHostName());
      workerRegistration.setPort(workerPort());
      workerRegistration.setActive(Boolean.TRUE);
      OffsetDateTime now = dateTimeSupport.nowOffsetUtc();
      workerRegistration.setRegisteredAt(now);
      workerRegistration.setLastHeartbeatAt(now);
      workerRegistration.setCapabilityTags(cfg.capabilityTags());
      registration = workerRuntimeFacade.start(workerRegistration);
      started.set(true);
      log.info(
          "{} worker started: workerId={}, tenantId={}",
          workerGroup(),
          registration.getWorkerId(),
          registration.getTenantId());
      return registration;
    }
  }

  @PreDestroy
  public void shutdown() {
    stopping.set(true);
    if (registration != null) {
      try {
        workerRuntimeFacade.shutdown(registration.getWorkerId());
      } catch (Exception ex) {
        log.warn(
            "{} worker shutdown signal failed: {} ({})",
            workerGroup(),
            ex.getMessage(),
            ex.getClass().getSimpleName());
      }
    }
  }

  @EventListener(ContextClosedEvent.class)
  public void onContextClosed(ContextClosedEvent event) {
    stopping.set(true);
  }

  private String buildWorkerId(WorkerConfiguration cfg) {
    if (Texts.hasText(cfg.workerCode())) {
      return cfg.workerCode();
    }
    if (Texts.hasText(cfg.workerType())) {
      return cfg.workerType().toLowerCase() + "-" + UUID.randomUUID();
    }
    return workerGroup() + "-" + UUID.randomUUID();
  }

  private String resolveHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ex) {
      SwallowedExceptionLogger.info(AbstractWorkerLoop.class, "catch:UnknownHostException", ex);

      return "localhost";
    }
  }

  /** 单行日志用：{@link Exception#getMessage()} 常为 null（如 RestClient I/O 失败），沿 cause 链拼可读摘要，不打堆栈。 */
  private static String summarizeRegistrationFailure(Throwable ex) {
    if (ex == null) {
      return "unknown";
    }
    String top = ex.getMessage();
    if (Texts.hasText(top)) {
      Throwable root = ex;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      if (root != ex) {
        String rm = root.getMessage();
        String rootPart =
            Texts.hasText(rm)
                ? root.getClass().getSimpleName() + ": " + rm
                : root.getClass().getSimpleName();
        return top + " [" + rootPart + "]" + registrationFailureHint(ex);
      }
      return top + registrationFailureHint(ex);
    }
    StringBuilder sb = new StringBuilder();
    for (Throwable t = ex; t != null && sb.length() < 500; t = t.getCause()) {
      if (sb.length() > 0) {
        sb.append(" <- ");
      }
      sb.append(t.getClass().getSimpleName());
      String m = t.getMessage();
      if (Texts.hasText(m)) {
        sb.append(": ").append(m);
      }
    }
    String core = sb.length() > 0 ? sb.toString() : ex.getClass().getSimpleName();
    return core + registrationFailureHint(ex);
  }

  private static String registrationFailureHint(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof ConnectException) {
        return "；原因：无法连上 Orchestrator（未启动、端口错误或未监听）";
      }
      if (t instanceof ClosedChannelException) {
        return "；原因：连接在建立过程中被关闭";
      }
      if (t instanceof UnknownHostException) {
        return "；原因：主机名无法解析";
      }
    }
    return "";
  }
}
