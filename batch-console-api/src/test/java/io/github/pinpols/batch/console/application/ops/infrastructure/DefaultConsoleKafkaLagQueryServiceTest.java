package io.github.pinpols.batch.console.application.ops.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleKafkaConsumerLagResponse;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListGroupsResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Kafka 积压查询错误信息保护")
class DefaultConsoleKafkaLagQueryServiceTest {
  @Test
  @DisplayName("单个消费组失败时响应和日志不泄露凭据")
  void shouldMaskCredentials_whenSingleGroupFails() {
    ConsoleQueryCacheService cache = mock(ConsoleQueryCacheService.class);
    when(cache.<List<ConsoleKafkaConsumerLagResponse>>getOrLoad(
            anyString(), any(),
            ArgumentMatchers.<TypeReference<List<ConsoleKafkaConsumerLagResponse>>>any(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    AdminClient admin = mock(AdminClient.class);
    ListGroupsResult groups = mock(ListGroupsResult.class);
    GroupListing group = mock(GroupListing.class);
    when(group.groupId()).thenReturn("batch-a");
    when(admin.listGroups()).thenReturn(groups);
    when(groups.all()).thenReturn(KafkaFuture.completedFuture(List.of(group)));
    when(admin.listConsumerGroupOffsets("batch-a"))
        .thenThrow(new IllegalStateException("password=demo-secret"));
    DefaultConsoleKafkaLagQueryService service = new DefaultConsoleKafkaLagQueryService(
        mock(KafkaAdmin.class), cache, new BatchKafkaTopicNamespace(""));
    ReflectionTestUtils.setField(service, "adminClient", admin);
    Logger logger = (Logger) LoggerFactory.getLogger(DefaultConsoleKafkaLagQueryService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      List<ConsoleKafkaConsumerLagResponse> result = service.consumerGroupLags(null);
      assertThat(result).hasSize(1);
      assertThat(result.get(0).groupId()).isEqualTo("batch-a");
      assertThat(result.get(0).error()).contains("password=").doesNotContain("demo-secret");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("demo-secret");
      assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("查询消费组列表失败时响应不暴露凭据")
  void shouldMaskCredentials_whenListingConsumerGroupsFails() throws Exception {
    ConsoleQueryCacheService cache = mock(ConsoleQueryCacheService.class);
    when(cache.<List<ConsoleKafkaConsumerLagResponse>>getOrLoad(
            anyString(),
            any(),
            ArgumentMatchers.<TypeReference<List<ConsoleKafkaConsumerLagResponse>>>any(),
            any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    AdminClient admin = mock(AdminClient.class);
    ListGroupsResult groups = mock(ListGroupsResult.class);
    @SuppressWarnings("unchecked")
    KafkaFuture<Collection<GroupListing>> future = mock(KafkaFuture.class);
    when(admin.listGroups()).thenReturn(groups);
    when(groups.all()).thenReturn(future);
    when(future.get(10, TimeUnit.SECONDS))
        .thenThrow(new ExecutionException(new IllegalStateException("password=demo-secret")));
    DefaultConsoleKafkaLagQueryService service = new DefaultConsoleKafkaLagQueryService(
        mock(KafkaAdmin.class), cache, new BatchKafkaTopicNamespace(""));
    ReflectionTestUtils.setField(service, "adminClient", admin);

    Logger logger = (Logger) LoggerFactory.getLogger(DefaultConsoleKafkaLagQueryService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      List<ConsoleKafkaConsumerLagResponse> result = service.consumerGroupLags(null);
      assertThat(result).hasSize(1);
      assertThat(result.get(0).error()).contains("password=").doesNotContain("demo-secret");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("demo-secret");
      assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("缓存命中的旧错误仍不暴露凭据")
  void shouldMaskCredentials_whenCachedErrorContainsSecrets() {
    ConsoleQueryCacheService cache = mock(ConsoleQueryCacheService.class);
    when(cache.<List<ConsoleKafkaConsumerLagResponse>>getOrLoad(
            anyString(),
            any(),
            ArgumentMatchers.<TypeReference<List<ConsoleKafkaConsumerLagResponse>>>any(),
            any()))
        .thenReturn(List.of(
            new ConsoleKafkaConsumerLagResponse("batch-a", null, null, null, "token=demo-secret"),
            new ConsoleKafkaConsumerLagResponse("batch-b", 0L, 2, null, null),
            new ConsoleKafkaConsumerLagResponse("batch-c", null, null, null, "access denied")));
    DefaultConsoleKafkaLagQueryService service = new DefaultConsoleKafkaLagQueryService(
        mock(KafkaAdmin.class), cache, new BatchKafkaTopicNamespace(""));

    List<ConsoleKafkaConsumerLagResponse> result = service.consumerGroupLags(null);
    assertThat(result.get(0).error()).contains("token=").doesNotContain("demo-secret");
    assertThat(result.get(1).totalLag()).isZero();
    assertThat(result.get(2).error()).isEqualTo("access denied");
  }
}
