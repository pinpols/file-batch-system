package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.service.BatchDayOperateCommand;
import com.example.batch.orchestrator.service.BatchDayOperationService;
import com.example.batch.orchestrator.service.BatchDayOperationService.BatchDayOperation;
import com.example.batch.orchestrator.service.BatchDayOperationService.BatchDayOperationResult;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量日治理内部接口（仅供 console 通过 ConsoleOrchestratorProxyService HTTP 转发调用）。
 *
 * <p>动作集合：FREEZE / RELEASE / SKIP / REOPEN / CLOSE，对应 {@link BatchDayOperationService} 的状态转换 + 审计双写
 * （`job_execution_log` + V105 `batch_day_operation_audit`）。RELEASE 会触发 `batch_day_waiting_launch`
 * 表的下一日重放（参见 §14.3.1）。
 *
 * <p>console-api 不能直接写状态表，必须经此 proxy 在 orchestrator 事务内推进。
 */
@RestController
@RequestMapping("/internal/batch-days")
@RequiredArgsConstructor
public class BatchDayOpsController {

  private final BatchDayOperationService batchDayOperationService;

  @PostMapping("/operate")
  public BatchDayOperateResponse operate(@RequestBody BatchDayOperateRequest request) {
    BatchDayOperateCommand command =
        BatchDayOperateCommand.builder()
            .tenantId(request.tenantId())
            .calendarCode(request.calendarCode())
            .bizDate(request.bizDate())
            .action(BatchDayOperation.valueOf(request.action()))
            .operatorId(request.operatorId())
            .reason(request.reason())
            .build();
    BatchDayOperationResult result = batchDayOperationService.operate(command);
    BatchDayInstanceEntity entity = result.batchDay();
    return new BatchDayOperateResponse(
        entity.id(),
        entity.dayStatus(),
        Boolean.TRUE.equals(entity.frozen()),
        result.releasedLaunchCount());
  }

  /**
   * @param action FREEZE / RELEASE / SKIP / REOPEN / CLOSE
   */
  public record BatchDayOperateRequest(
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String action,
      String operatorId,
      String reason) {}

  public record BatchDayOperateResponse(
      Long batchDayId, String dayStatus, boolean frozen, int releasedLaunchCount) {}
}
