package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.contract.response.BatchDayCatchupControlResponse;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupControlService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupPreview;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupPreviewService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayHistoricalGapInventory;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayCatchupControlEntity;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only preview of the next bounded batch-day cursor scan. */
@RestController
@RequestMapping("/internal/batch-days/catch-up-preview")
@RequiredArgsConstructor
public class BatchDayCatchupPreviewController {

  private final BatchDayCatchupPreviewService previewService;
  private final BatchDayCatchupControlService controlService;

  @GetMapping
  public BatchDayCatchupPreview preview(
      @RequestParam String tenantId, @RequestParam String calendarCode) {
    return previewService.preview(tenantId, calendarCode);
  }

  @GetMapping("/historical-gaps")
  public BatchDayHistoricalGapInventory inventoryHistoricalGaps(
      @RequestParam String tenantId,
      @RequestParam String calendarCode,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate throughDate) {
    return previewService.inventoryHistoricalGaps(tenantId, calendarCode, fromDate, throughDate);
  }

  @PostMapping("/control")
  public BatchDayCatchupControlResponse changeControl(@RequestBody CatchupControlRequest request) {
    BatchDayCatchupControlEntity control =
        controlService.change(new BatchDayCatchupControlService.PauseCommand(
            request.tenantId(),
            request.calendarCode(),
            request.action(),
            request.operatorId(),
            request.reason()));
    return BatchDayCatchupControlResponse.from(control);
  }

  public record CatchupControlRequest(
      String tenantId, String calendarCode, String action, String operatorId, String reason) {}
}
