package io.github.pinpols.batch.worker.imports.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.runtime.ImportEventArrivalService;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    return new ImportEventArrivalController(
        new ImportEventArrivalService(importIngressScanner, properties, meterRegistry));
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
  @DisplayName("扫描总开关关闭时不报告已触发或增加扫描计数")
  void shouldNotReportScan_whenScannerDisabled() {
    ImportScannerProperties properties = new ImportScannerProperties();
    properties.setEnabled(false);
    properties.getEventArrival().setEnabled(true);
    ImportEventArrivalController controller = new ImportEventArrivalController(
        new ImportEventArrivalService(importIngressScanner, properties, meterRegistry));

    CommonResponse<ImportEventArrivalResponse> response = controller.objectArrival(null);

    assertThat(response.data().triggered()).isFalse();
    assertThat(response.data().reason()).isEqualTo("scanner-disabled");
    assertThat(meterRegistry.counter("batch.import.event_arrival.notifications").count())
        .isEqualTo(1);
    assertThat(meterRegistry.find("batch.import.event_arrival.scans").counter()).isNull();
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
  @DisplayName("扫描失败后释放在途标记，后续通知可以再次触发")
  void shouldAllowNextNotification_whenScanFails() {
    ImportEventArrivalController controller = controller(true);
    doThrow(new IllegalStateException("scan failed"))
        .doNothing()
        .when(importIngressScanner)
        .scan();

    assertThatThrownBy(() -> controller.objectArrival(null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(controller.objectArrival(null).data().triggered()).isTrue();
    verify(importIngressScanner, times(2)).scan();
  }

  @Test
  @DisplayName("扫描在途时合并并发通知，完成后允许再次扫描")
  void shouldCoalesceNotificationsWhileScanIsInFlight() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
          entered.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("scan was not released");
          }
          return null;
        })
        .when(importIngressScanner)
        .scan();
    ImportEventArrivalController controller = controller(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> controller.objectArrival(null));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CommonResponse<ImportEventArrivalResponse> concurrent = controller.objectArrival(null);
        assertThat(concurrent.data().triggered()).isFalse();
        assertThat(concurrent.data().reason()).isEqualTo("scan-already-in-flight");
      } finally {
        release.countDown();
      }
      assertThat(first.get(5, TimeUnit.SECONDS).data().triggered()).isTrue();
      assertThat(controller.objectArrival(null).data().triggered()).isTrue();
    }
    verify(importIngressScanner, times(2)).scan();
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
