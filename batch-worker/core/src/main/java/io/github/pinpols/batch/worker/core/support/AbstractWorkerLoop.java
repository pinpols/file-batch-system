package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.constants.WorkerCapabilities;
import io.github.pinpols.batch.common.dto.WorkerTaskCapabilityDto;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerIdentityProperties;
import io.github.pinpols.batch.worker.core.config.WorkerRegistryStartupProperties;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import jakarta.annotation.PreDestroy;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Worker 生命周期模板（所有 worker 通用骨架）。
 *
 * <p>使用方式：
 *
 * <ul>
 *   <li>子类只提供差异化配置：{@link #workerConfiguration()} / {@link #workerGroup()}
 *   <li>子类用一个很薄的 {@code @Scheduled} 方法定期调用 {@link #doHeartbeat()}（避免在抽象类里硬编码配置 key）
 * </ul>
 *
 * <p>该模板负责：
 *
 * <ul>
 *   <li>应用启动后自动注册（{@link #onReady()}）
 *   <li>周期心跳（{@link #doHeartbeat()}）
 *   <li>优雅下线（{@link #shutdown()}）
 * </ul>
 */
@Slf4j
public abstract class AbstractWorkerLoop implements EnvironmentAware {

  private final WorkerLifecycleManager workerLifecycleManager;
  private final HeartbeatService heartbeatService;
  private final BatchDateTimeSupport dateTimeSupport;
  private final WorkerIdentityProperties identityProperties;
  private final int maxConcurrentTasks;
  private final boolean failFastOnStartup;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private final AtomicReference<WorkerRegistration> registration = new AtomicReference<>();

  /**
   * Spring 回调注入运行时环境。端口只采用主 WebServer 绑定后写入的 local.server.port，
   * 不采用 server.port 配置值或独立 management 服务的 local.management.port。
   */
  private Environment environment;

  protected AbstractWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      int maxConcurrentTasks) {
    this(
        workerLifecycleManager,
        heartbeatService,
        dateTimeSupport,
        maxConcurrentTasks,
        new WorkerIdentityProperties(),
        new WorkerRegistryStartupProperties());
  }

  protected AbstractWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      int maxConcurrentTasks,
      WorkerIdentityProperties identityProperties) {
    this(
        workerLifecycleManager,
        heartbeatService,
        dateTimeSupport,
        maxConcurrentTasks,
        identityProperties,
        new WorkerRegistryStartupProperties());
  }

  protected AbstractWorkerLoop(
      WorkerLifecycleManager workerLifecycleManager,
      HeartbeatService heartbeatService,
      BatchDateTimeSupport dateTimeSupport,
      int maxConcurrentTasks,
      WorkerIdentityProperties identityProperties,
      WorkerRegistryStartupProperties workerRegistryStartupProperties) {
    this.workerLifecycleManager = workerLifecycleManager;
    this.heartbeatService = heartbeatService;
    this.dateTimeSupport = dateTimeSupport;
    this.maxConcurrentTasks = maxConcurrentTasks;
    this.identityProperties = identityProperties;
    this.failFastOnStartup = workerRegistryStartupProperties.isFailFastOnStartup();
  }

  /** Worker 配置（topic、tenantId、workerType 等）。 */
  protected abstract WorkerConfiguration workerConfiguration();

  /** worker 逻辑分组，如 {@code import}/{@code export}/{@code dispatch}。 */
  protected abstract String workerGroup();

  /** Worker 进程可选上报的执行器能力；默认不声明。 */
  protected List<WorkerTaskCapabilityDto> taskCapabilities() {
    return List.of();
  }

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  /**
   * 注册上报端口必须来自 Spring 主 WebServer 的实际绑定结果。
   *
   * <p>{@code local.server.port} 由 Spring Boot 在 WebServer 真正绑定后写入 Environment，因此 {@code
   * server.port=0}（随机端口）也能上报正确结果。心跳或消费者提前触发注册时，若服务尚未绑定则拒绝注册，
   * 后续沿既有重试路径再次尝试，避免把配置端口当作监听成功的证据。
   */
  private int resolveWorkerPort() {
    int port = EmptyChecks.isNull(environment)
        ? -1
        : Objects.requireNonNullElse(
            environment.getProperty("local.server.port", Integer.class), -1);
    if (port < 1 || port > 65535) {
      throw new IllegalStateException("worker main HTTP server has no valid bound port");
    }
    return port;
  }

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
          SwallowedExceptionLogger.summary(ex));
      return;
    }
    try {
      if (stopping.get()) {
        return;
      }
      heartbeatService.beat(current.getWorkerId());
    } catch (Exception ex) {
      log.warn(
          "{} worker heartbeat unavailable: {} ({})",
          workerGroup(),
          SwallowedExceptionLogger.summary(ex),
          ex.getClass().getSimpleName());
    }
  }

  /** 幂等启动：首次调用注册 worker，后续调用直接返回注册信息；双重检查加锁保证线程安全。 */
  public WorkerRegistration ensureStarted() {
    if (started.get()) {
      return registration.get();
    }
    synchronized (this) {
      if (started.get()) {
        return registration.get();
      }
      WorkerConfiguration cfg = workerConfiguration();
      String workerPoolCode = resolveWorkerPoolCode(cfg);
      WorkerRegistration workerRegistration = new WorkerRegistration();
      workerRegistration.setWorkerId(buildWorkerId(workerPoolCode));
      workerRegistration.setWorkerCode(workerPoolCode);
      workerRegistration.setTenantId(cfg.tenantId());
      workerRegistration.setWorkerType(cfg.workerType());
      // 源头归一 workerGroup 为大写，避免 IMPORT / import 同语义字符串被 ResourceScheduler 等值比较误失配
      workerRegistration.setWorkerGroup(CodeNormalizer.toUpperOrNull(workerGroup()));
      workerRegistration.setHost(resolveHostName());
      workerRegistration.setPort(resolveWorkerPort());
      OffsetDateTime now = dateTimeSupport.nowOffsetUtc();
      workerRegistration.setRegisteredAt(now);
      workerRegistration.setLastHeartbeatAt(now);
      workerRegistration.setMaxConcurrent(maxConcurrentTasks);
      workerRegistration.setCapabilityTags(
          Stream.concat(cfg.capabilityTags().stream(), Stream.of(WorkerCapabilities.DRY_RUN_SAFE))
              .distinct()
              .toList());
      workerRegistration.setTaskCapabilities(taskCapabilities());
      WorkerRegistration startedRegistration = workerLifecycleManager.start(workerRegistration);
      registration.set(startedRegistration);
      started.set(true);
      log.info(
          "{} worker started: workerId={}, tenantId={}",
          workerGroup(),
          startedRegistration.getWorkerId(),
          startedRegistration.getTenantId());
      return startedRegistration;
    }
  }

  @PreDestroy
  public void shutdown() {
    stopping.set(true);
    WorkerRegistration current = registration.get();
    if (current != null) {
      try {
        workerLifecycleManager.shutdown(current.getWorkerId());
      } catch (Exception ex) {
        log.warn(
            "{} worker shutdown signal failed: {} ({})",
            workerGroup(),
            SwallowedExceptionLogger.summary(ex),
            ex.getClass().getSimpleName());
      }
    }
  }

  @EventListener(ContextClosedEvent.class)
  public void onContextClosed(ContextClosedEvent event) {
    stopping.set(true);
  }

  // S2259 无法识别 Texts.hasText(instanceId) 已在使用前排除 null。
  @SuppressWarnings("java:S2259")
  private String buildWorkerId(String poolCode) {
    String instanceId =
        EmptyChecks.isNull(identityProperties) ? null : identityProperties.getInstanceId();
    if (!Texts.hasText(instanceId)) {
      return poolCode;
    }
    String normalizedInstanceId = instanceId.replaceAll("[^a-zA-Z0-9._-]", "_");
    String combined = poolCode + "-" + normalizedInstanceId;
    if (combined.length() <= 128) {
      return combined;
    }
    int suffixLength = Math.min(normalizedInstanceId.length(), 64);
    String suffix = normalizedInstanceId.substring(normalizedInstanceId.length() - suffixLength);
    int poolLength = Math.max(1, 127 - suffixLength);
    return poolCode.substring(0, Math.min(poolCode.length(), poolLength)) + "-" + suffix;
  }

  private String resolveWorkerPoolCode(WorkerConfiguration cfg) {
    if (Texts.hasText(cfg.workerCode())) {
      return cfg.workerCode();
    }
    if (Texts.hasText(cfg.workerType())) {
      return cfg.workerType().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
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
        String rootPart = Texts.hasText(rm)
            ? root.getClass().getSimpleName() + ": " + rm
            : root.getClass().getSimpleName();
        return top + " [" + rootPart + "]" + registrationFailureHint(ex);
      }
      return top + registrationFailureHint(ex);
    }
    StringBuilder sb = new StringBuilder();
    for (Throwable t = ex; t != null && sb.length() < 500; t = t.getCause()) {
      if (EmptyChecks.isNotEmpty(sb)) {
        sb.append(" <- ");
      }
      sb.append(t.getClass().getSimpleName());
      String m = t.getMessage();
      if (Texts.hasText(m)) {
        sb.append(": ").append(m);
      }
    }
    String core = EmptyChecks.isNotEmpty(sb) ? sb.toString() : ex.getClass().getSimpleName();
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
