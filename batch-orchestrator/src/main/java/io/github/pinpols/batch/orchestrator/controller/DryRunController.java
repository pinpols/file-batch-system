package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.application.service.dryrun.DryRunPlanRequest;
import io.github.pinpols.batch.orchestrator.application.service.dryrun.DryRunPlanResult;
import io.github.pinpols.batch.orchestrator.application.service.dryrun.DryRunPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-026 演练计划内部控制器：{@code POST /internal/orchestrator/dry-run/plan}
 *
 * <p>console / API 调用方传 {@link DryRunPlanRequest}（含 {@code level=L1/L2/L3}），返回 {@link
 * DryRunPlanResult}：findings 列表 + 汇总 summary。<b>不</b>触发任何 launch / 不写 instance / 不调外部投递。
 */
@RestController
@RequestMapping("/internal/orchestrator/dry-run")
@RequiredArgsConstructor
public class DryRunController {

  private final DryRunPlanService dryRunPlanService;

  @PostMapping("/plan")
  public CommonResponse<DryRunPlanResult> plan(@RequestBody DryRunPlanRequest request) {
    return CommonResponse.success(dryRunPlanService.plan(request));
  }
}
