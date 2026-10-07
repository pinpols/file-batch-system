package io.github.pinpols.batch.worker.imports.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("事件驱动到达通知端点")
class ImportEventArrivalControllerTest {

  @Mock
  private ImportIngressScanner importIngressScanner;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ImportEventArrivalController controller(boolean eventArrivalEnabled) {
    ImportScannerProperties properties = new ImportScannerProperties();
    properties.getEventArrival().setEnabled(eventArrivalEnabled);
    return new ImportEventArrivalController(importIngressScanner, properties, meterRegistry);
  }

  @Test
  @DisplayName("开关关闭时不扫描,回 triggered=false")
  void shouldNotScan_whenDisabled() {
    // act
    CommonResponse<ImportEventArrivalResponse> response =
        controller(false).objectArrival(new ObjectArrivalNotification());

    // assert
    assertThat(response.data().triggered()).isFalse();
    assertThat(response.data().reason()).isEqualTo("event-arrival-disabled");
    verify(importIngressScanner, never()).scan();
  }

  @Test
  @DisplayName("开关开启时即时触发一次扫描,回 triggered=true")
  void shouldScanOnce_whenEnabled() {
    // arrange
    ObjectArrivalNotification notification = new ObjectArrivalNotification();
    notification.setTenantId("t1");
    notification.setBucket("ingress");
    notification.setObjectKey("ingress/import-20260620-orders.csv");

    // act
    CommonResponse<ImportEventArrivalResponse> response =
        controller(true).objectArrival(notification);

    // assert
    assertThat(response.data().triggered()).isTrue();
    assertThat(response.data().reason()).isNull();
    verify(importIngressScanner, times(1)).scan();
    assertThat(meterRegistry.find("batch.import.event_arrival.scans").counter()).isNotNull();
  }

  @Test
  @DisplayName("null body 开启时仍能触发扫描,不 NPE")
  void shouldHandleNullBody_whenEnabled() {
    // act
    CommonResponse<ImportEventArrivalResponse> response = controller(true).objectArrival(null);

    // assert
    assertThat(response.data().triggered()).isTrue();
    verify(importIngressScanner, times(1)).scan();
  }

  @Test
  @DisplayName("成功响应省略 reason，未触发时保留原因字段")
  void shouldPreserveReasonPresence_whenResponsesAreSerialized() {
    JsonMapper mapper = JsonMapper.builder().build();
    String success = mapper.writeValueAsString(controller(true).objectArrival(null));
    String disabled = mapper.writeValueAsString(controller(false).objectArrival(null));

    assertThat(success).contains("\"triggered\":true").doesNotContain("\"reason\"");
    assertThat(disabled).contains("\"triggered\":false", "\"reason\":\"event-arrival-disabled\"");
  }
}
