package io.github.pinpols.batch.console.domain.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultConsoleNotificationApplicationServiceTest {

  private ConsoleTenantGuard tenantGuard;
  private ConsoleRequestMetadataResolver metadataResolver;
  private NotificationChannelMapper channelMapper;
  private SubscriptionRuleMapper ruleMapper;
  private NotificationDeliveryLogMapper deliveryLogMapper;
  private DefaultConsoleNotificationApplicationService service;

  @BeforeEach
  void setUp() {
    tenantGuard = mock(ConsoleTenantGuard.class);
    metadataResolver = mock(ConsoleRequestMetadataResolver.class);
    channelMapper = mock(NotificationChannelMapper.class);
    ruleMapper = mock(SubscriptionRuleMapper.class);
    deliveryLogMapper = mock(NotificationDeliveryLogMapper.class);
    service =
        new DefaultConsoleNotificationApplicationService(
            tenantGuard, metadataResolver, channelMapper, ruleMapper, deliveryLogMapper);
    when(tenantGuard.resolveTenant("tenant-a")).thenReturn("tenant-a");
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata(
                "req-1", "trace-1", "tenant-a", "operator-1", "idem-1", "127.0.0.1"));
  }

  @Test
  void shouldListChannels() {
    when(channelMapper.selectByTenant("tenant-a"))
        .thenReturn(List.of(Map.of("channelCode", "email-1")));

    List<Map<String, Object>> result = service.listChannels("tenant-a");

    assertThat(result).hasSize(1);
    assertThat(result.get(0)).containsEntry("channelCode", "email-1");
  }

  @Test
  void shouldGetChannel() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(Map.of("channelCode", "email-1"));

    Map<String, Object> result = service.getChannel("tenant-a", "email-1");

    assertThat(result).containsEntry("channelCode", "email-1");
  }

  @Test
  void shouldThrowWhenChannelMissing() {
    when(channelMapper.selectByCode("tenant-a", "missing")).thenReturn(null);

    assertThatThrownBy(() -> service.getChannel("tenant-a", "missing"))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 仅 key,改为基于 messageKey 含 not_found 或 messageArgs 含 not found
        .satisfies(
            ex -> {
              BizException bx = (BizException) ex;
              boolean keyMatch =
                  bx.getMessageKey() != null && bx.getMessageKey().contains("not_found");
              boolean argsMatch =
                  bx.getMessageArgs() != null
                      && java.util.Arrays.stream(bx.getMessageArgs())
                          .anyMatch(
                              a -> a != null && a.toString().toLowerCase().contains("not found"));
              assertThat(keyMatch || argsMatch)
                  .as("messageKey or args should imply not_found")
                  .isTrue();
            });
  }

  @Test
  void shouldCreateChannel() {
    when(channelMapper.selectByCode("tenant-a", "email-1")).thenReturn(null);

    service.createChannel(
        "tenant-a",
        channelUpsert(
            "email-1", "Email One", "EMAIL", "{\"url\":\"https://example.com/hook\"}", true));

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(channelMapper).insert(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("channelCode", "email-1")
        .containsEntry("channelName", "Email One")
        .containsEntry("channelType", "EMAIL")
        .containsEntry("configJson", "{\"url\":\"https://example.com/hook\"}")
        .containsEntry("enabled", true)
        .containsEntry("createdBy", "operator-1")
        .containsEntry("updatedBy", "operator-1");
  }

  @Test
  void shouldRejectDuplicateChannelCode() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(Map.of("channelCode", "email-1"));

    assertThatThrownBy(
            () ->
                service.createChannel(
                    "tenant-a", channelUpsert("email-1", "Email One", "EMAIL", null, true)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("code_already_exists");
  }

  @Test
  void shouldUpdateChannel() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(Map.of("channelCode", "email-1"));

    NotificationChannelUpdateRequest update = new NotificationChannelUpdateRequest();
    update.setChannelName("Email Updated");
    update.setChannelType("EMAIL");
    update.setConfigJson("{\"url\":\"https://example.com/new\"}");
    update.setEnabled(false);
    service.updateChannel("tenant-a", "email-1", update);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(channelMapper).update(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("channelCode", "email-1")
        .containsEntry("channelName", "Email Updated")
        .containsEntry("channelType", "EMAIL")
        .containsEntry("configJson", "{\"url\":\"https://example.com/new\"}")
        .containsEntry("enabled", false)
        .containsEntry("updatedBy", "operator-1");
  }

  @Test
  void shouldCreateRule() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(Map.of("channelCode", "email-1"));

    service.createRule(
        "tenant-a",
        ruleUpsert("default-rule", "email-1", "JOB_SUCCESS,JOB_FAILED", "HIGH", "JOB-*", true));

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(ruleMapper).insert(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("ruleName", "default-rule")
        .containsEntry("channelCode", "email-1")
        .containsEntry("eventTypes", "JOB_SUCCESS,JOB_FAILED")
        .containsEntry("severityFilter", "HIGH")
        .containsEntry("jobCodeFilter", "JOB-*")
        .containsEntry("enabled", true)
        .containsEntry("createdBy", "operator-1")
        .containsEntry("updatedBy", "operator-1");
  }

  @Test
  void shouldRejectRuleWhenChannelMissing() {
    when(channelMapper.selectByCode("tenant-a", "missing")).thenReturn(null);

    assertThatThrownBy(
            () ->
                service.createRule(
                    "tenant-a",
                    ruleUpsert("default-rule", "missing", "JOB_SUCCESS", null, null, true)))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 仅 key,改为基于 messageKey 含 not_found 或 messageArgs 含 not found
        .satisfies(
            ex -> {
              BizException bx = (BizException) ex;
              boolean keyMatch =
                  bx.getMessageKey() != null && bx.getMessageKey().contains("not_found");
              boolean argsMatch =
                  bx.getMessageArgs() != null
                      && java.util.Arrays.stream(bx.getMessageArgs())
                          .anyMatch(
                              a -> a != null && a.toString().toLowerCase().contains("not found"));
              assertThat(keyMatch || argsMatch)
                  .as("messageKey or args should imply not_found")
                  .isTrue();
            });
  }

  @Test
  void shouldUpdateRule() {
    when(ruleMapper.selectById("tenant-a", 7L)).thenReturn(Map.of("id", 7L));

    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(Map.of("channelCode", "email-1"));
    service.updateRule(
        "tenant-a", 7L, ruleUpsert("updated-rule", "email-1", "JOB_FAILED", "LOW", "JOB-1", false));

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(ruleMapper).update(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("id", 7L)
        .containsEntry("ruleName", "updated-rule")
        .containsEntry("channelCode", "email-1")
        .containsEntry("eventTypes", "JOB_FAILED")
        .containsEntry("severityFilter", "LOW")
        .containsEntry("jobCodeFilter", "JOB-1")
        .containsEntry("enabled", false)
        .containsEntry("updatedBy", "operator-1");
  }

  @Test
  void shouldThrowWhenRuleMissing() {
    when(ruleMapper.selectById("tenant-a", 99L)).thenReturn(null);

    assertThatThrownBy(
            () ->
                service.updateRule(
                    "tenant-a", 99L, ruleUpsert("r", "email-1", "JOB_FAILED", null, null, true)))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 仅 key,改为基于 messageKey 含 not_found 或 messageArgs 含 not found
        .satisfies(
            ex -> {
              BizException bx = (BizException) ex;
              boolean keyMatch =
                  bx.getMessageKey() != null && bx.getMessageKey().contains("not_found");
              boolean argsMatch =
                  bx.getMessageArgs() != null
                      && java.util.Arrays.stream(bx.getMessageArgs())
                          .anyMatch(
                              a -> a != null && a.toString().toLowerCase().contains("not found"));
              assertThat(keyMatch || argsMatch)
                  .as("messageKey or args should imply not_found")
                  .isTrue();
            });
  }

  private static NotificationChannelUpsertRequest channelUpsert(
      String channelCode,
      String channelName,
      String channelType,
      String configJson,
      Boolean enabled) {
    NotificationChannelUpsertRequest req = new NotificationChannelUpsertRequest();
    req.setChannelCode(channelCode);
    req.setChannelName(channelName);
    req.setChannelType(channelType);
    req.setConfigJson(configJson);
    req.setEnabled(enabled);
    return req;
  }

  private static SubscriptionRuleUpsertRequest ruleUpsert(
      String ruleName,
      String channelCode,
      String eventTypes,
      String severityFilter,
      String jobCodeFilter,
      Boolean enabled) {
    SubscriptionRuleUpsertRequest req = new SubscriptionRuleUpsertRequest();
    req.setRuleName(ruleName);
    req.setChannelCode(channelCode);
    req.setEventTypes(eventTypes);
    req.setSeverityFilter(severityFilter);
    req.setJobCodeFilter(jobCodeFilter);
    req.setEnabled(enabled);
    return req;
  }

  @Test
  void shouldReturnLogsAndCapLimit() {
    when(deliveryLogMapper.selectByTenant("tenant-a", 500)).thenReturn(List.of(Map.of("id", 1L)));

    List<Map<String, Object>> logs = service.deliveryLogs("tenant-a", 999);

    assertThat(logs).hasSize(1);
    verify(deliveryLogMapper).selectByTenant("tenant-a", 500);
  }

  @Test
  void shouldTestChannelAndWriteLog() {
    when(channelMapper.selectByCode("tenant-a", "email-1"))
        .thenReturn(Map.of("channelCode", "email-1"));

    Map<String, Object> result = service.testChannel("tenant-a", "email-1");

    assertThat(result)
        .containsEntry("channelCode", "email-1")
        .containsEntry("status", "OK")
        .containsEntry("message", "test notification dispatched");
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
    verify(deliveryLogMapper).insert(captor.capture());
    assertThat(captor.getValue())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("ruleId", 0)
        .containsEntry("channelCode", "email-1")
        .containsEntry("eventType", "TEST")
        .containsEntry("deliveryStatus", "SUCCESS")
        .containsEntry("attempt", 1);
  }
}
