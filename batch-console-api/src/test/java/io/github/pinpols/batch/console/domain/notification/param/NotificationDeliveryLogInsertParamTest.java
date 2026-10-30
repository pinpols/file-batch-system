package io.github.pinpols.batch.console.domain.notification.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("通知投递日志参数：必填身份和可空字段")
class NotificationDeliveryLogInsertParamTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tenantId",
        "ruleId",
        "channelCode",
        "eventType",
        "payloadJson",
        "deliveryStatus",
        "attempt"
      })
  @DisplayName("每个数据库必填字段缺失时构造立即失败")
  void shouldRejectMissingRequiredField_whenBuilding(String field) {
    var builder = validBuilder();
    switch (field) {
      case "tenantId" -> builder.tenantId(null);
      case "ruleId" -> builder.ruleId(null);
      case "channelCode" -> builder.channelCode(null);
      case "eventType" -> builder.eventType(null);
      case "payloadJson" -> builder.payloadJson(null);
      case "deliveryStatus" -> builder.deliveryStatus(null);
      case "attempt" -> builder.attempt(null);
      default -> throw new IllegalArgumentException(field);
    }
    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(field);
  }

  @Test
  @DisplayName("直接调用record构造器也不能绕过必填租户")
  void shouldRejectMissingTenant_whenCallingCanonicalConstructor() {
    assertThatThrownBy(() -> new NotificationDeliveryLogInsertParam(
            null, 0L, "ops", "TEST", null, "{}", "SUCCESS", null, 1))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("tenantId");
  }

  @Test
  @DisplayName("可空告警ID与错误摘要仍可省略")
  void shouldAcceptOptionalFields_whenBuildingValidRecord() {
    NotificationDeliveryLogInsertParam param = validBuilder().build();
    assertThat(param.alertEventId()).isNull();
    assertThat(param.errorMessage()).isNull();
  }

  private static NotificationDeliveryLogInsertParam.NotificationDeliveryLogInsertParamBuilder
      validBuilder() {
    return NotificationDeliveryLogInsertParam.builder()
        .tenantId("tenant-a")
        .ruleId(0L)
        .channelCode("ops")
        .eventType("TEST")
        .payloadJson("{}")
        .deliveryStatus("SUCCESS")
        .attempt(1);
  }
}
