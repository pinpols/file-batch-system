package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessResult;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上游就绪查询内部接口(ADR-043 依赖感知 fire)。仅供 trigger 内部调用,不对外暴露。
 *
 * <p>trigger 携 X-Internal-Secret 经本只读 API 查就绪,不直查 job_instance。
 *
 * <p>守 CLAUDE.md「Orchestrator 唯一状态主机 + trigger 严禁直连状态表」。
 */
@RestController
@RequestMapping("/internal/readiness")
@RequiredArgsConstructor
public class OrchestratorReadinessController {

  private final ReadinessService readinessService;

  /**
   * 查上游 job 在指定批次日是否就绪(已有 EFFECTIVE asset partition)。
   *
   * @return {@link ReadinessResult},{@code ready=true} 表示上游该批次日结果版本已生效,响应同时带 asset/version 明细
   */
  @GetMapping("/job")
  public ReadinessResult checkJob(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("jobCode") String jobCode,
      @RequestParam("bizDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
    return readinessService.checkJobReady(tenantId, jobCode, bizDate);
  }
}
