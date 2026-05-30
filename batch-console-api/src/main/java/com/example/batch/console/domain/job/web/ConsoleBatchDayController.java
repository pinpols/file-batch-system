package com.example.batch.console.domain.job.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.job.web.request.BatchDayOperateRequest;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量日治理控制台入口。统一 POST /api/console/batch-days/operate，转发 orchestrator 内部接口。
 *
 * <p>权限点：高风险动作仅 ROLE_ADMIN 可调用；REOPEN / CLOSE 可考虑后续接审批，本控制器只做转发，状态机和审计在 orchestrator 端。
 *
 * <p>幂等：类级 @Idempotent 防双击重复触发；orchestrator 端的乐观锁兜住状态机 race condition。
 */
@RestController
@Validated
@RequestMapping("/api/console/batch-days")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleBatchDayController {

  private final ConsoleOrchestratorProxyService orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/operate")
  public CommonResponse<Map<String, Object>> operate(
      @Valid @RequestBody BatchDayOperateRequest request) {
    Map<String, Object> result =
        orchestratorProxyService.batchDayOperate(
            request.getTenantId(),
            request.getCalendarCode(),
            request.getBizDate(),
            request.getAction(),
            request.getOperatorId(),
            request.getReason());
    return responseFactory.success(result);
  }
}
