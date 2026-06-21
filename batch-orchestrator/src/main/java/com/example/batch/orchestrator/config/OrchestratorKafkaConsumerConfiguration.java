package com.example.batch.orchestrator.config;

import com.example.batch.common.exception.BizException;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * ADR-010 Stage 4: orchestrator Kafka 消费侧配置（固化无开关）。
 *
 * <p>消费模式:MANUAL_IMMEDIATE ack(consumer 端处理完才 ack)。
 *
 * <p><b>错误处理 (poison-pill skip)</b>：注入 {@link DefaultErrorHandler} 替换默认无限重试：
 *
 * <ul>
 *   <li>瞬时错误（PG 抖动 / Kafka 短暂不可用）：固定 backoff（默认 3 次 × 2s）后跳过当前 offset，避免一条挂死整个 partition；
 *   <li>永久错误（jobCode 不存在 / 协议反序列化失败 / 校验失败）：注册 {@link BizException} + {@link
 *       IllegalArgumentException} 为 not-retryable，命中即跳过 — 业务错不会无限重试；
 *   <li>recover 阶段仅 ERROR 日志（LOG_ONLY），不发 DLT。如未来要落 DLT topic，注入 {@code
 *       DeadLetterPublishingRecoverer} 替换 lambda 即可。
 * </ul>
 *
 * <p><b>历史教训</b>：2026-05-07 STRICT smoke 期间一条 jobCode 拼错的 launch 消息在 trigger.launch.v1 上无限重试， 占满
 * orchestrator KafkaListener 线程并通过 batch-scheduler 线程池连锁让 WaitingPartitionDispatchScheduler 抢不到调度权
 * — 所有 worker 链路被卡住。明确把业务异常列为 not-retryable 后，这类错一次就跳过 offset。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableKafka
@RequiredArgsConstructor
public class OrchestratorKafkaConsumerConfiguration {

  /** Listener factory bean 名称 — 给 @KafkaListener(containerFactory=...) 引用。 */
  public static final String TRIGGER_LISTENER_FACTORY =
      "triggerLaunchKafkaListenerContainerFactory";

  private final TriggerConsumerProperties consumerProperties;

  @Bean
  public ConsumerFactory<String, String> triggerLaunchConsumerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerProperties.getGroupId());
    // earliest 回退:首次起服 / 重置时不丢消息;正常运行靠 commit offset
    properties.put(
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumerProperties.getAutoOffsetReset());
    // 关 auto-commit,走 MANUAL_IMMEDIATE
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, consumerProperties.getMaxPollRecords());
    properties.put(
        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, consumerProperties.getMaxPollIntervalMs());
    return new DefaultKafkaConsumerFactory<>(properties);
  }

  @Bean(name = TRIGGER_LISTENER_FACTORY)
  public ConcurrentKafkaListenerContainerFactory<String, String>
      triggerLaunchKafkaListenerContainerFactory(
          ConsumerFactory<String, String> triggerLaunchConsumerFactory,
          ObservationRegistry observationRegistry) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(triggerLaunchConsumerFactory);
    // launch→实例创建吞吐受此并发约束(旧默认 1 单线程封顶多租峰值流量);与 launch topic 分区数对齐
    factory.setConcurrency(consumerProperties.getConcurrency());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setObservationRegistry(observationRegistry);
    factory.setCommonErrorHandler(triggerLaunchErrorHandler());
    return factory;
  }

  /**
   * Poison-pill 防护用 ErrorHandler。瞬时错误重试 N 次后放行；业务错（BizException /
   * IllegalArgumentException）不重试直接放行。日志记录失败上下文，offset 前进，避免单条长期停滞 partition。
   */
  private DefaultErrorHandler triggerLaunchErrorHandler() {
    TriggerConsumerProperties.ErrorHandler eh = consumerProperties.getErrorHandler();
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (record, exception) -> {
              // BizException / IllegalArgumentException 是预期的业务级拒绝(jobCode 不存在/跨租拒/字段缺失),
              // 用 WARN 即可,不需要 ERROR 占用告警通道;系统级 transient 错误重试到上限才是真 ERROR
              Throwable cause = unwrap(exception);
              boolean businessLevel =
                  cause instanceof BizException || cause instanceof IllegalArgumentException;
              if (businessLevel) {
                log.warn(
                    "TriggerLaunchConsumer 业务错跳过: topic={} partition={} offset={} key={} cause={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    cause.getMessage());
              } else {
                log.error(
                    "TriggerLaunchConsumer 消息已超出重试上限: topic={} partition={} offset={}"
                        + " key={} value={} cause={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.value(),
                    exception.getMessage());
              }
            },
            new FixedBackOff(eh.getRetryBackoffMs(), Math.max(0, eh.getRetryAttempts() - 1)));
    // 业务异常一次跳过：jobCode 不存在 / 协议字段缺失 / 跨租拒绝 等都不可能靠重试恢复
    handler.addNotRetryableExceptions(BizException.class);
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }

  /** Spring Kafka listener 异常会被包成 ListenerExecutionFailedException;剥到真实 cause 判定业务级。 */
  private static Throwable unwrap(Throwable t) {
    Throwable current = t;
    while (current != null
        && current.getCause() != null
        && current.getCause() != current
        && !(current instanceof BizException)
        && !(current instanceof IllegalArgumentException)) {
      current = current.getCause();
    }
    return current == null ? t : current;
  }
}
