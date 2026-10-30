package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.param.NotificationDeliveryLogInsertParam;
import io.github.pinpols.batch.console.domain.notification.view.NotificationDeliveryLogRow;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** 在真实PG中验证投递审计具名入参、JSON与租户查询边界。 */
@SpringBootTest(classes = BatchConsoleApiApplication.class)
@DisplayName("通知投递日志具名写入：字段、空值和租户查询")
class NotificationDeliveryTypedMapperIntegrationTest extends AbstractIntegrationTest {
  private final NotificationDeliveryLogMapper mapper;

  @Autowired
  NotificationDeliveryTypedMapperIntegrationTest(NotificationDeliveryLogMapper mapper) {
    this.mapper = mapper;
  }

  @Test
  @Transactional
  @DisplayName("保留大整数身份、成功和失败摘要，查询不返回其他租户")
  void shouldBindDeliveryRows_andFilterOtherTenants() {
    String tenant = "delivery-typed-" + Long.toUnsignedString(System.nanoTime());
    long largeRuleId = 9_007_199_254_740_993L;
    NotificationDeliveryLogInsertParam success = NotificationDeliveryLogInsertParam.builder()
        .tenantId(tenant)
        .ruleId(0L)
        .channelCode("ops")
        .eventType("TEST")
        .alertEventId(null)
        .payloadJson("{\"event\":\"fixture\"}")
        .deliveryStatus("SUCCESS")
        .errorMessage(null)
        .attempt(1)
        .build();
    NotificationDeliveryLogInsertParam failed = NotificationDeliveryLogInsertParam.builder()
        .tenantId(tenant)
        .ruleId(largeRuleId)
        .channelCode("mail")
        .eventType("JOB_FAILED")
        .alertEventId(null)
        .payloadJson("{\"job\":\"fixture\"}")
        .deliveryStatus("FAILED")
        .errorMessage("投递失败")
        .attempt(3)
        .build();
    NotificationDeliveryLogInsertParam otherTenant = NotificationDeliveryLogInsertParam.builder()
        .tenantId(tenant + "-other")
        .ruleId(17L)
        .channelCode("other")
        .eventType("TEST")
        .payloadJson("{}")
        .deliveryStatus("SUCCESS")
        .attempt(1)
        .build();
    assertThat(mapper.insert(success)).isEqualTo(1);
    assertThat(mapper.insert(failed)).isEqualTo(1);
    assertThat(mapper.insert(otherTenant)).isEqualTo(1);
    mapper.bindTenant(tenant);
    List<NotificationDeliveryLogRow> rows = mapper.selectByTenant(tenant, 100);
    assertThat(rows)
        .hasSize(2)
        .extracting(NotificationDeliveryLogRow::ruleId)
        .containsExactlyInAnyOrder(0L, largeRuleId);
    NotificationDeliveryLogRow savedSuccess =
        rows.stream().filter(row -> row.ruleId().equals(0L)).findFirst().orElseThrow();
    NotificationDeliveryLogRow savedFailure = rows.stream()
        .filter(row -> row.ruleId().equals(largeRuleId))
        .findFirst()
        .orElseThrow();
    assertThat(savedSuccess.tenantId()).isEqualTo(tenant);
    assertThat(savedSuccess.deliveryStatus()).isEqualTo("SUCCESS");
    assertThat(savedSuccess.errorMessage()).isNull();
    assertThat(savedSuccess.alertEventId()).isNull();
    assertThat(savedSuccess.payloadJson()).contains("fixture");
    assertThat(savedFailure.channelCode()).isEqualTo("mail");
    assertThat(savedFailure.eventType()).isEqualTo("JOB_FAILED");
    assertThat(savedFailure.deliveryStatus()).isEqualTo("FAILED");
    assertThat(savedFailure.errorMessage()).isEqualTo("投递失败");
    assertThat(savedFailure.attempt()).isEqualTo(3);
  }
}
