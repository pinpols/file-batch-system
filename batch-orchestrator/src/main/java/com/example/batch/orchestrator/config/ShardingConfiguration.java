package com.example.batch.orchestrator.config;

import com.example.batch.orchestrator.infrastructure.sharding.RedisShardAssignmentProvider;
import com.example.batch.orchestrator.infrastructure.sharding.ShardAssignmentProvider;
import com.example.batch.orchestrator.infrastructure.sharding.StaticShardAssignmentProvider;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox shard 分配源的装配：按 {@link OutboxProperties#getShardingMode()} 选择
 * {@link StaticShardAssignmentProvider}（默认）或 {@link RedisShardAssignmentProvider}（动态）。
 *
 * <p>DYNAMIC 模式下 {@link RedisShardAssignmentProvider} 自己注入 @Scheduled 心跳，
 * 所以这里需要 {@link EnableScheduling}（但项目其他位置已经启用了，不会重复）。
 */
@Slf4j
@Configuration
@EnableScheduling
public class ShardingConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ShardAssignmentProvider shardAssignmentProvider(
      OutboxProperties outboxProperties, StringRedisTemplate stringRedisTemplate) {
    if (outboxProperties.getShardingMode() == OutboxProperties.ShardingMode.DYNAMIC) {
      String memberId = resolveMemberId(outboxProperties);
      Duration memberTtl = Duration.ofMillis(outboxProperties.getSharding().getMemberTtlMs());
      log.info(
          "Outbox sharding mode=DYNAMIC, memberId={}, memberTtl={}s", memberId, memberTtl.toSeconds());
      return new RedisShardAssignmentProvider(stringRedisTemplate, memberId, memberTtl);
    }
    log.info(
        "Outbox sharding mode=STATIC, shardTotal={}, shardIndex={}",
        outboxProperties.getShardTotal(),
        outboxProperties.getShardIndex());
    return new StaticShardAssignmentProvider(outboxProperties);
  }

  private String resolveMemberId(OutboxProperties props) {
    String configured = props.getSharding().getMemberId();
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    // K8s Downward API 注入的 POD_NAME 优先；否则 hostname 兜底
    String pod = System.getenv("POD_NAME");
    if (pod != null && !pod.isBlank()) {
      return pod;
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "orchestrator-unknown-" + System.nanoTime();
    }
  }
}
