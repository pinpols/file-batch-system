package com.example.batch.worker.imports.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.worker.imports.config.ImportScannerProperties;
import com.example.batch.worker.imports.runtime.ImportIngressScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("事件驱动到达通知端点")
class ImportEventArrivalControllerTest {

  @Mock private ImportIngressScanner importIngressScanner;

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
    CommonResponse<Map<String, Object>> response =
        controller(false).objectArrival(new ObjectArrivalNotification());

    // assert
    assertThat(response.data()).containsEntry("triggered", false);
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
    CommonResponse<Map<String, Object>> response = controller(true).objectArrival(notification);

    // assert
    assertThat(response.data()).containsEntry("triggered", true);
    verify(importIngressScanner, times(1)).scan();
    assertThat(meterRegistry.find("batch.import.event_arrival.scans").counter()).isNotNull();
  }

  @Test
  @DisplayName("null body 开启时仍能触发扫描,不 NPE")
  void shouldHandleNullBody_whenEnabled() {
    // act
    CommonResponse<Map<String, Object>> response = controller(true).objectArrival(null);

    // assert
    assertThat(response.data()).containsEntry("triggered", true);
    verify(importIngressScanner, times(1)).scan();
  }
}
