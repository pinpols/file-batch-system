package io.github.pinpols.batch.trigger.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchInput;
import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("触发请求转换保留外部事件幂等快照")
class TriggerCommandInputMapperTest {

  @Test
  @DisplayName("旧快照可解析为相同输入，新快照保留既有 JSON 字段")
  void shouldPreserveExternalEventSnapshot() throws Exception {
    String historicalSnapshot = """
        {"tenantId":"t1","externalEventCode":"PAY","externalEventId":"event-1",
         "bizDate":[2026,3,27],"params":{"amount":3},"dataIntervalStart":null,
         "dataIntervalEnd":null,"dryRun":false}
        """;
    ExternalEventLaunchRequest request = new ExternalEventLaunchRequest();
    request.setTenantId("t1");
    request.setExternalEventCode("PAY");
    request.setExternalEventId("event-1");
    request.setBizDate(LocalDate.of(2026, 3, 27));
    request.setParams(Map.of("amount", 3));
    request.setDryRun(false);

    ExternalEventLaunchInput input = TriggerCommandInputMapper.toInput(request);
    ExternalEventLaunchInput historical =
        JsonUtils.fromJsonStrict(historicalSnapshot, ExternalEventLaunchInput.class);

    assertThat(input).isEqualTo(historical);
    assertThat(JsonUtils.newDefaultMapper().readTree(JsonUtils.toJson(input)))
        .isEqualTo(JsonUtils.newDefaultMapper().readTree(historicalSnapshot));
  }
}
