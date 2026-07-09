package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerWebhookPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertmanagerAlertRendererTest {

  private final AlertmanagerAlertRenderer renderer = new AlertmanagerAlertRenderer();

  private static AlertmanagerAlert alert(
      String status, Map<String, String> labels, Map<String, String> annotations) {
    return new AlertmanagerAlert(
        status, labels, annotations, "2026-07-08T10:00:00Z", null, null, "fp");
  }

  @Test
  void rendersSingleFiringAlertWithSummaryAndDescription() {
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            "gk",
            0,
            "firing",
            "batch-dispatch",
            Map.of("alertname", "DispatchBacklogHigh"),
            Map.of("alertname", "DispatchBacklogHigh", "severity", "critical"),
            Map.of(),
            "http://am",
            List.of(
                alert(
                    "firing",
                    Map.of(
                        "alertname",
                        "DispatchBacklogHigh",
                        "severity",
                        "critical",
                        "instance",
                        "worker-1"),
                    Map.of("summary", "backlog too high", "description", "queue depth 5000"))));

    RenderedAlertNotification rendered = renderer.render(payload, 50);

    assertThat(rendered.title())
        .isEqualTo("[FIRING] batch-dispatch · DispatchBacklogHigh (1 alert)");
    assertThat(rendered.body())
        .contains("- [FIRING] DispatchBacklogHigh severity=critical instance=worker-1")
        .contains("summary: backlog too high")
        .contains("description: queue depth 5000");
    assertThat(rendered.structured())
        .containsEntry("receiver", "batch-dispatch")
        .containsEntry("status", "firing")
        .containsEntry("alertCount", 1)
        .containsEntry("severity", "critical");
  }

  @Test
  void groupsMultipleAlertsIntoOneSummary() {
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            "gk",
            0,
            "firing",
            "batch-sla",
            Map.of("alert_group", "sla"),
            Map.of("alertname", "SlaBreach"),
            Map.of(),
            null,
            List.of(
                alert("firing", Map.of("alertname", "SlaBreach", "instance", "a"), Map.of()),
                alert("firing", Map.of("alertname", "SlaBreach", "instance", "b"), Map.of())));

    RenderedAlertNotification rendered = renderer.render(payload, 50);

    assertThat(rendered.title()).isEqualTo("[FIRING] batch-sla · SlaBreach (2 alerts)");
    assertThat(rendered.body()).contains("instance=a").contains("instance=b");
    assertThat(rendered.structured()).containsEntry("alertCount", 2);
  }

  @Test
  void rendersResolvedStatus() {
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            "gk",
            0,
            "resolved",
            "batch-sre",
            Map.of(),
            Map.of("alertname", "CpuHigh"),
            Map.of(),
            null,
            List.of(alert("resolved", Map.of("alertname", "CpuHigh"), Map.of())));

    RenderedAlertNotification rendered = renderer.render(payload, 50);

    assertThat(rendered.title()).isEqualTo("[RESOLVED] batch-sre · CpuHigh (1 alert)");
    assertThat(rendered.body()).contains("- [RESOLVED] CpuHigh");
  }

  @Test
  void toleratesMissingFieldsWithPlaceholders() {
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(new AlertmanagerAlert(null, null, null, null, null, null, null)));

    RenderedAlertNotification rendered = renderer.render(payload, 50);

    assertThat(rendered.title()).isEqualTo("[-] - · - (1 alert)");
    assertThat(rendered.body()).contains("- [-] - severity=- instance=-");
  }

  @Test
  void truncatesBeyondMaxAlerts() {
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            "gk",
            0,
            "firing",
            "batch-default",
            Map.of(),
            Map.of("alertname", "Noise"),
            Map.of(),
            null,
            List.of(
                alert("firing", Map.of("alertname", "Noise", "instance", "1"), Map.of()),
                alert("firing", Map.of("alertname", "Noise", "instance", "2"), Map.of()),
                alert("firing", Map.of("alertname", "Noise", "instance", "3"), Map.of())));

    RenderedAlertNotification rendered = renderer.render(payload, 1);

    assertThat(rendered.body()).contains("instance=1").doesNotContain("instance=2");
    assertThat(rendered.body()).contains("... and 2 more");
    // alertCount 保留原始总量,但 alertnames 只累积展开的 shown 条(防超大批量下无界膨胀)。
    assertThat(rendered.structured()).containsEntry("alertCount", 3);
    assertThat(rendered.structured().get("alertnames")).isEqualTo(List.of("Noise"));
  }

  @Test
  void boundsAlertnamesToMaxAlerts_whenOversizedBatch() {
    List<AlertmanagerAlert> many = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      many.add(
          alert("firing", Map.of("alertname", "Flood", "instance", String.valueOf(i)), Map.of()));
    }
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            "gk",
            0,
            "firing",
            "batch-default",
            Map.of(),
            Map.of("alertname", "Flood"),
            Map.of(),
            null,
            many);

    RenderedAlertNotification rendered = renderer.render(payload, 50);

    assertThat(rendered.structured()).containsEntry("alertCount", 500);
    assertThat((List<?>) rendered.structured().get("alertnames")).hasSize(50);
    assertThat(rendered.body()).contains("... and 450 more");
  }

  @Test
  void emptyAlertsListDoesNotThrow() {
    AlertmanagerWebhookPayload payload =
        new AlertmanagerWebhookPayload(
            "4",
            "gk",
            0,
            "firing",
            "batch-default",
            Map.of(),
            Map.of("alertname", "None"),
            Map.of(),
            null,
            null);

    RenderedAlertNotification rendered = renderer.render(payload, 50);

    assertThat(rendered.title()).isEqualTo("[FIRING] batch-default · None (0 alerts)");
    assertThat(rendered.structured()).containsEntry("alertCount", 0);
  }
}
