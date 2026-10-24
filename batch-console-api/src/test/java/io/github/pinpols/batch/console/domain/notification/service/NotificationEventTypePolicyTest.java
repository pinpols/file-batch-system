package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationEventTypePolicyTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"*", "JOB_SUCCESS,WORKFLOW_FAILED", "audit.custom", "JOB_SLA_CUSTOM"})
  void preservesOtherEventTypesAndExistingRequiredValidation(String eventTypes) {
    assertThatCode(() -> NotificationEventTypePolicy.requireBusinessEventTypes(eventTypes))
        .doesNotThrowAnyException();
  }
}
