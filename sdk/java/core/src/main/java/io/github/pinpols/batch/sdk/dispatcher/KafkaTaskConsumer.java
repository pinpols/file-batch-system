package io.github.pinpols.batch.sdk.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.client.BatchSdkClientException;
import io.github.pinpols.batch.sdk.internal.ThrottledLogger;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Kafka task dispatch topic consumer — 单线程 poll loop,把消息反序列化为 {@link TaskDispatchMessage} 后扔给
 * {@link TaskDispatcher}。
 *
 * <p>关键约束:
 *
 * <ul>
 *   <li>**手动 commit**(enable.auto.commit=false):只有 dispatcher 明确提交到线程池或消息本身是终态坏消息时才 commit
 *       offset;平台暂停 / 本地 drain / tenant mismatch 不前移 offset,避免 consume-and-drop
 *   <li>{@link BatchPlatformClientConfig#getKafkaTopicPattern()} 支持
 *       wildcard(`batch.task.dispatch.<tenant>.*`)
 *   <li>JSON 反序列化失败 → log ERROR + skip,不无限循环
 * </ul>
 */
@Slf4j
public class KafkaTaskConsumer implements Runnable, AutoCloseable {

  private final BatchPlatformClientConfig config;
  private final TaskDispatcher dispatcher;
  private final ObjectMapper objectMapper;
  private final Consumer<String, byte[]> consumer;
  private final AtomicBoolean running = new AtomicBoolean(true);

  /**
   * P1-4 #1.7:poll loop 因非预期 Throwable 退出时置 true。 与 {@link #running}=false 区分:running=false 可能是正常
   * {@link #close()},crashed=true 表示无法继续消费,{@link
   * io.github.pinpols.batch.sdk.client.BatchPlatformClient#isHealthy()} 据此报 false 让 K8s liveness
   * probe 重启进程。
   */
  private final AtomicBoolean crashed = new AtomicBoolean(false);

  /**
   * Lane E #4-Java:Kafka SASL 认证失败(凭据错误)时置 true。 与 {@link #crashed} 区分:crashed=任意非预期
   * Throwable;fatalAuthFailure=确定不可恢复的认证错, 无限重试也修不好,必须 fail-fast 让 K8s 拉起重启;{@link
   * io.github.pinpols.batch.sdk.client.BatchPlatformClient#stop(java.time.Duration)} 据此跳过
   * deactivate(凭据已坏,HTTP 也会 401)。
   */
  private final AtomicBoolean fatalAuthFailure = new AtomicBoolean(false);

  /**
   * 未知 schema 大版本被拒后走 RETRY_LATER(§A 不提交 offset)→ seek+pause,分区 resume 后会反复重读重拒; 节流该 WARN(同 key 60s
   * 一条),避免一条 v3 poison 把日志刷爆。
   */
  private final ThrottledLogger throttledLog = ThrottledLogger.create(log, Duration.ofSeconds(60));

  /**
   * Lane E #5:消费线程引用 —— {@link #close(Duration)} 用来 join 等其退出,确保 offset commit / {@code
   * consumer.close()} 完成,避免 SIGKILL 时 in-flight offset 丢失。 由 {@link #run()} 进入时记录。
   */
  private volatile Thread kafkaThread;

  /**
   * P0 hardening:**容量维度** pause —— in-flight 达上限(或平台 PAUSED/DRAINING)时 pause 整个 assignment;掉下来再
   * resume。Zeebe maxJobsActive 模式。仅记账容量/平台这一类 pause,**不**覆盖 poison/RETRY_LATER 的 per-partition
   * pause(见 {@link #poisonPausedPartitions}),否则容量 resume 会误把 poison 分区一起 resume → 重读被 seek 的 poison
   * 记录 → RETRY_LATER 忙旋转。
   */
  private volatile boolean paused = false;

  /**
   * #9 修复:被 RETRY_LATER(未知 schema / dispatcher 暂留)seek + pause 的 poison 分区集合,与容量/平台 pause({@link
   * #paused}) 分开记账。容量 resume **只** resume 非 poison 分区,绝不动这里的分区,避免「容量正常→resume 整个 assignment→重 poll
   * 到被 seek 的 poison 记录→再 RETRY_LATER」的忙旋转。这类分区维持 HOL 暂停,直到 SDK 升级 / 平台回退(§A fail-loud 本意)。 poll
   * 线程与 rebalance 回调单线程触碰, 但单测从测试线程调 {@link #applyBackpressure()},故用线程安全 set。
   */
  private final Set<TopicPartition> poisonPausedPartitions = ConcurrentHashMap.newKeySet();

  /**
   * P7-1:最近一次 poll 后读到的 Kafka {@code records-lag-max}(所有 assigned partition 的最大滞后条数)。 {@code -1}
   * 表示尚未知(未 poll 过 / consumer 未暴露该指标)。只在 poll 线程写,{@code volatile} 供 {@link #consumerLagMax()} 跨线程读
   * —— 避免在 metrics 线程直接碰非线程安全的 {@link Consumer}。
   */
  private volatile long consumerLagMax = -1L;

  public KafkaTaskConsumer(BatchPlatformClientConfig config, TaskDispatcher dispatcher) {
    this(
        config,
        dispatcher,
        defaultConsumer(config),
        new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
  }

  /** test-friendly ctor:可注入 mock Consumer(含 {@code MockConsumer})。 */
  KafkaTaskConsumer(
      BatchPlatformClientConfig config,
      TaskDispatcher dispatcher,
      Consumer<String, byte[]> consumer,
      ObjectMapper objectMapper) {
    this.config = config;
    this.dispatcher = dispatcher;
    this.consumer = consumer;
    this.objectMapper = objectMapper;
  }

  private static KafkaConsumer<String, byte[]> defaultConsumer(BatchPlatformClientConfig config) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrap());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getKafkaGroupId());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    // P3:per-tenant Kafka SASL/SCRAM(ACL 路径)。三个字段都置空 → 走 PLAINTEXT(本地联调);
    // 任一非空 → 全部按设值传给 Kafka client(prod 必填 protocol + mechanism + jaasConfig)。
    if (notBlank(config.getKafkaSecurityProtocol())) {
      props.put("security.protocol", config.getKafkaSecurityProtocol());
    }
    if (notBlank(config.getKafkaSaslMechanism())) {
      props.put("sasl.mechanism", config.getKafkaSaslMechanism());
    }
    if (notBlank(config.getKafkaSaslJaasConfig())) {
      props.put("sasl.jaas.config", config.getKafkaSaslJaasConfig());
    }
    return new KafkaConsumer<>(props);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  /** 启动 poll loop。阻塞当前线程,通常在专用线程跑。 */
  @Override
  public void run() {
    this.kafkaThread = Thread.currentThread();
    consumer.subscribe(
        Pattern.compile(config.getKafkaTopicPattern()), new PauseAwareRebalanceListener());
    log.info(
        "KafkaTaskConsumer started: tenant={}, topicPattern={}, group={}",
        config.getTenantId(),
        config.getKafkaTopicPattern(),
        config.getKafkaGroupId());

    try {
      while (running.get()) {
        applyBackpressure();
        ConsumerRecords<String, byte[]> records = consumer.poll(config.getKafkaPollInterval());
        refreshConsumerLag();
        if (records.isEmpty()) continue;
        for (ConsumerRecord<String, byte[]> rec : records) {
          if (!handleRecordAndMaybeCommit(rec)) {
            break;
          }
        }
      }
    } catch (AuthenticationException authEx) {
      // Lane E #4-Java:Kafka SASL 凭据错 —— 不可恢复,fail-fast。
      // 无限重试只会让 Pod hang 到 K8s liveness 杀;主动置 fatalAuthFailure + running=false,
      // BatchPlatformClient.stop() 据此跳过 deactivate(凭据已错,HTTP 也会 401),
      // 抛 RuntimeException 让 Pod 以非 0 码退出,K8s 用正确凭据重启拉起。
      fatalAuthFailure.set(true);
      running.set(false);
      log.error(
          "Kafka SASL authentication failed; entering fatal state. "
              + "Check BATCH_KAFKA_* credentials. Pod will exit for K8s to restart.",
          authEx);
      throw new BatchSdkClientException(
          BatchSdkClientException.Stage.KAFKA_AUTH,
          "Kafka SASL auth failed — pod should restart with correct creds",
          authEx);
    } catch (WakeupException wakeup) {
      // 正常 stop 触发
    } catch (Throwable t) {
      // P1-4 #1.7:非预期退出 — 置 crashed + running=false,让 BatchPlatformClient.isHealthy() 报 false,
      // 不静默死。K8s liveness probe / 运维监控由此感知到 worker 实质已停消费。
      crashed.set(true);
      running.set(false);
      log.error("KafkaTaskConsumer poll loop died (marked crashed)", t);
    } finally {
      try {
        consumer.close();
      } catch (Exception e) {
        log.warn("kafka consumer close error: {}", e.getMessage());
      }
      log.info("KafkaTaskConsumer stopped");
    }
  }

  /**
   * P0 hardening(borrowed from Zeebe maxJobsActive):in-flight 已满则 pause assigned partitions,
   * 队列降到一半以下再 resume。注意 pause/resume 是按 partition 维度,**不停 poll loop**(否则 consumer heartbeat 也会停 →
   * consumer group rebalance 把当前 worker 踢)。
   *
   * <p>Round-3 #1(ADR-035 §11.1 / Round-2 P0 #1 闭环):resume 阈值带 hysteresis —— pause 在 {@code
   * inFlight >= max},resume 只有当 {@code inFlight < max * 0.5}(整数除法即 {@code max / 2}) 时才触发。 上下边界拉开,避免
   * in-flight 在 max-1 / max 间快速抖动时 pause/resume 反复颠簸 Kafka client(每次 resume 都触发一次新
   * fetch,频繁切换会浪费带宽且让 records-lag-max 失真)。Zeebe maxJobsActive 默认 activation threshold 也是这个模式。
   *
   * <p>Phase 2 §2.4:平台 PAUSED / DRAINING(见 {@link TaskDispatcher#platformAcceptsNewTasks()})也触发同样的
   * partition pause —— 用 pause 而非 consume-and-drop,offset 不前进,平台恢复 NORMAL 后从原位继续消费,不丢任务。 平台层 resume
   * 不受 hysteresis 影响:只要平台再次 accept,立即 resume(不存在抖动来源)。
   */
  void applyBackpressure() {
    int max = config.getMaxConcurrentTasks();
    // P0:用 submittedCount(queued+claiming+running)而非 inFlightCount(只数 CLAIM 成功后的)。
    // claim 慢 / 平台 5xx 时 worker 卡在 claim、inFlight 偏低,只有 submitted 能如实反映在制量并及时 pause。
    int inFlight = dispatcher.submittedCount();
    boolean platformPaused =
        !dispatcher.platformAcceptsNewTasks() || dispatcher.isFatal() || dispatcher.isDraining();
    // 容量维度 pause:inFlight 达到上限;resume:inFlight 跌破 max/2(hysteresis 防抖)
    boolean capacityPause = inFlight >= max;
    boolean capacityResumeOk = inFlight < Math.max(1, max / 2);
    if (capacityPause || platformPaused) {
      if (!paused && !consumer.assignment().isEmpty()) {
        consumer.pause(consumer.assignment());
        paused = true;
        log.info(
            "consumer pause: inFlight={} max={} platformState={}",
            inFlight,
            max,
            dispatcher.platformState());
      }
    } else if (paused && !platformPaused && capacityResumeOk) {
      // #9 修复:容量 resume **只** resume 非 poison 分区。若 resume 整个 assignment,会把被 RETRY_LATER seek+pause
      // 的
      // poison 分区一起放开,下一轮 poll 重读被 seek 的 poison 记录再 RETRY_LATER → 忙旋转。poison 分区维持 HOL 暂停。
      Set<TopicPartition> toResume = resumableCapacityPartitions();
      if (!toResume.isEmpty()) {
        consumer.resume(toResume);
      }
      paused = false;
      log.info(
          "consumer resume: inFlight={} max={} platformState={} resumed={} poisonPaused={} "
              + "(below {}*0.5 hysteresis)",
          inFlight,
          max,
          dispatcher.platformState(),
          toResume.size(),
          poisonPausedPartitions.size(),
          max);
    }
  }

  /** 当前 assignment 去掉 poison-paused 的分区集合 —— 容量 resume 的目标(不放开 poison 分区)。 */
  private Set<TopicPartition> resumableCapacityPartitions() {
    Set<TopicPartition> assignment = consumer.assignment();
    if (poisonPausedPartitions.isEmpty()) {
      return assignment;
    }
    Set<TopicPartition> out = new HashSet<>(assignment);
    out.removeAll(poisonPausedPartitions);
    return out;
  }

  /**
   * P7-1:在 poll 线程读 Kafka client 自带的 {@code records-lag-max} 指标(consumer-fetch-manager-metrics
   * group)写入 {@link #consumerLagMax}。只在 poll 线程触碰 {@link Consumer},满足其单线程约束;指标缺失(如刚启动 / mock
   * consumer)时保持上一次值不动。
   */
  private void refreshConsumerLag() {
    try {
      for (Map.Entry<MetricName, ? extends Metric> e : consumer.metrics().entrySet()) {
        if ("records-lag-max".equals(e.getKey().name())) {
          Object value = e.getValue().metricValue();
          if (value instanceof Number n) {
            double d = n.doubleValue();
            // Kafka 无数据时给 NaN / -Inf,只在拿到有限非负值时更新
            if (Double.isFinite(d) && d >= 0) {
              consumerLagMax = (long) d;
            }
          }
          return;
        }
      }
    } catch (RuntimeException ex) {
      log.debug("refreshConsumerLag skipped: {}", ex.getMessage());
    }
  }

  boolean handleRecordAndMaybeCommit(ConsumerRecord<String, byte[]> rec) {
    TaskDispatcher.DispatchDecision decision = handleRecord(rec);
    TopicPartition tp = new TopicPartition(rec.topic(), rec.partition());
    if (decision == TaskDispatcher.DispatchDecision.RETRY_LATER) {
      try {
        consumer.seek(tp, rec.offset());
        consumer.pause(Set.of(tp));
        // #9 修复:记进 poison 集而非置容量 paused=true。容量 resume 据此排除该分区,避免被一并 resume 后重读
        // 被 seek 的 poison 记录 → 再 RETRY_LATER 忙旋转。
        poisonPausedPartitions.add(tp);
      } catch (Exception ex) {
        log.warn(
            "failed to seek/pause retry-later record topic={}, partition={}, offset={}: {}",
            rec.topic(),
            rec.partition(),
            rec.offset(),
            ex.getMessage());
      }
      return false;
    }
    try {
      consumer.commitSync(Map.of(tp, new OffsetAndMetadata(rec.offset() + 1)));
      return true;
    } catch (Exception ex) {
      log.warn(
          "kafka commitSync failed topic={}, partition={}, offset={} (will retry next poll): {}",
          rec.topic(),
          rec.partition(),
          rec.offset(),
          ex.getMessage());
      return false;
    }
  }

  TaskDispatcher.DispatchDecision handleRecord(ConsumerRecord<String, byte[]> rec) {
    if (rec.value() == null || rec.value().length == 0) {
      log.warn("empty kafka message at topic={}, offset={}, skipping", rec.topic(), rec.offset());
      return TaskDispatcher.DispatchDecision.DROP_TERMINAL;
    }
    TaskDispatchMessage msg;
    try {
      msg = objectMapper.readValue(rec.value(), TaskDispatchMessage.class);
    } catch (Exception ex) {
      log.error(
          "failed to parse kafka task dispatch message at topic={}, offset={}: {}",
          rec.topic(),
          rec.offset(),
          ex.getMessage());
      return TaskDispatcher.DispatchDecision.DROP_TERMINAL;
    }
    // Phase 0 §2.1:reject 未知 major schema(避免老 SDK 误解平台新 v3 消息)。
    // wire-protocol §A 硬契约:未知大版本 **不提交 offset**(RETRY_LATER),而非 DROP_TERMINAL——
    // 提交会静默跳过该 v3 任务;不提交则该消息 HOL 阻塞分区直到 SDK 升级(§A 本意:fail-loud,
    // 逼迫升级)。对齐 Go(DispositionRejectedSchema 不提交)+ Python(RETRY_LATER)。正常情况下
    // v3 本不该被投到 v2-only worker(consumer-group / 能力协商前置拦截),此分支只在协商失效时触发。
    if (!msg.isSchemaSupported()) {
      throttledLog.warn(
          "unsupported_schema",
          "rejecting kafka task dispatch message with unsupported schemaVersion={} at topic={},"
              + " offset={}, taskId={}; offset withheld per wire-protocol §A, upgrade SDK",
          msg.schemaVersion(),
          rec.topic(),
          rec.offset(),
          msg.taskId());
      return TaskDispatcher.DispatchDecision.RETRY_LATER;
    }
    TaskDispatcher.DispatchDecision decision = dispatcher.onMessage(msg);
    return decision == null ? TaskDispatcher.DispatchDecision.RETRY_LATER : decision;
  }

  /** 让 poll loop 退出。WakeupException 在 run() 被捕获 → close consumer。默认 5s join 超时。 */
  @Override
  public void close() {
    close(Duration.ofSeconds(5));
  }

  /**
   * Lane E #5:wakeup poll loop + join 等其退出,保证 offset commit / {@code consumer.close()} 在 K8s
   * SIGKILL 前完成。 join 超时仅打 WARN(不抛),消费线程后续会自然清理;BatchPlatformClient 的总预算控制最坏耗时。
   *
   * <p>幂等:多次调用只第一次触发 wakeup。
   */
  public void close(Duration joinTimeout) {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    log.info("KafkaTaskConsumer wakeup requested");
    try {
      consumer.wakeup();
    } catch (Exception ex) {
      log.warn("kafka consumer.wakeup() failed: {}", ex.getMessage());
    }
    Thread t = this.kafkaThread;
    if (t != null && t != Thread.currentThread()) {
      long timeoutMs = Math.max(100L, joinTimeout == null ? 5_000L : joinTimeout.toMillis());
      try {
        t.join(timeoutMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        log.warn("Kafka close interrupted while waiting for poll thread", ie);
        return;
      }
      if (t.isAlive()) {
        log.warn(
            "Kafka poll thread did not exit within {}ms after wakeup; "
                + "offsets may be uncommitted. K8s SIGKILL may cause task replay.",
            timeoutMs);
      }
    }
  }

  /** 暴露给测试:当前 running 状态。 */
  boolean isRunning() {
    return running.get();
  }

  /**
   * P1-4 #1.7:poll loop 是否因非预期 Throwable 退出。正常 {@link #close()} 不会让此返回 true。 供 {@link
   * io.github.pinpols.batch.sdk.client.BatchPlatformClient#isHealthy()} 判定。
   */
  public boolean hasCrashed() {
    return crashed.get();
  }

  /**
   * Lane E #4-Java:poll loop 是否因 Kafka SASL 认证失败退出。{@link
   * io.github.pinpols.batch.sdk.client.BatchPlatformClient#stop(java.time.Duration)} 据此跳过
   * deactivate(凭据已坏,HTTP 也会 401,空喊无意义)。
   */
  public boolean isFatalAuthFailure() {
    return fatalAuthFailure.get();
  }

  /**
   * P7-1:最近一次 poll 观测到的最大 consumer lag(条数),{@code -1} = 未知。供 {@link
   * io.github.pinpols.batch.sdk.client.BatchPlatformClient#metrics()} 透出给租户监控。
   */
  public long consumerLagMax() {
    return consumerLagMax;
  }

  /** 给 BatchPlatformClient 注入额外 properties(实际项目会扩展 SASL / SSL 等)。 */
  public static Properties augmentSecurityProperties(
      BatchPlatformClientConfig config, Properties extra) {
    Map<String, Object> merged = new HashMap<>();
    for (String k : extra.stringPropertyNames()) {
      merged.put(k, extra.getProperty(k));
    }
    Properties out = new Properties();
    out.putAll(merged);
    return out;
  }

  /**
   * Phase 1 §3.1 #1.3:rebalance 后 Kafka 不保留 partition 级别的 pause 状态, 新分到的 partition 默认 RESUMED。如果
   * backpressure 期间发生 rebalance, 必须在 {@code onPartitionsAssigned} 立刻重新 pause 新拿到的 partition, 否则
   * inFlight 已满时也会拉新消息,绕过 maxConcurrent 上限。
   */
  final class PauseAwareRebalanceListener implements ConsumerRebalanceListener {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
      log.info("kafka partitions revoked: {}", partitions);
      // #9:撤走的分区不再归我们,清掉其 poison 记账(重新分配后会从平台/新 SDK 重新决策)。
      poisonPausedPartitions.removeAll(partitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
      log.info("kafka partitions assigned: {}", partitions);
      if (partitions.isEmpty()) {
        return;
      }
      // 容量/平台 backpressure 仍生效 → 重新 pause 全部新分区(Kafka rebalance 后默认 RESUMED)。
      if (paused) {
        consumer.pause(partitions);
        log.info(
            "re-paused {} newly assigned partition(s) after rebalance (backpressure still active)",
            partitions.size());
        return;
      }
      // #9:即使容量正常,仍要保持 poison 分区 pause —— 否则 rebalance 会让其默认 RESUMED 进而重读 poison 记录。
      Set<TopicPartition> poisonReassigned = new HashSet<>(partitions);
      poisonReassigned.retainAll(poisonPausedPartitions);
      if (!poisonReassigned.isEmpty()) {
        consumer.pause(poisonReassigned);
        log.info(
            "re-paused {} poison-paused partition(s) after rebalance (HOL block until SDK upgrade)",
            poisonReassigned.size());
      }
    }
  }
}
