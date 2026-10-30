package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("通知事件类型边界与必填校验")
class NotificationEventTypePolicyTest {

  @DisplayName("保留其它事件类型及既有必填校验")
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"*", "JOB_SUCCESS,WORKFLOW_FAILED", "audit.custom", "JOB_SLA_CUSTOM"})
  void shouldPreserveOtherTypesAndValidation_whenApplyingPolicy(String eventTypes) {
    assertThatCode(() -> NotificationEventTypePolicy.requireBusinessEventTypes(eventTypes))
        .doesNotThrowAnyException();
  }
}
