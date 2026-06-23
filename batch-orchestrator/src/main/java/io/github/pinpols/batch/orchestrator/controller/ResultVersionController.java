package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-017 Stage 6 result_version 内部控制器。{@code /internal/orchestrator/result-versions}.
 *
 * <p>console-api 通过 RestClient 转发；提供 4 类操作：
 *
 * <ol>
 *   <li>{@code GET /} — 按 (tenantId, businessKey, limit) 列举所有版本（含 PENDING / EFFECTIVE / SUPERSEDED
 *       / ARCHIVED / DRY_RUN）；
 *   <li>{@code GET /{id}} — 单版本详情，含完整 payload_json；
 *   <li>{@code POST /{id}/promote} — PENDING → EFFECTIVE（手工 + 审批驱动）；
 *   <li>{@code POST /{id}/reject} — PENDING → ARCHIVED（候选放弃）。
 * </ol>
 *
 * <p>所有写路径走 {@link ResultVersionPromoteService}，由其内部保证 EFFECTIVE 单版唯一约束（partial unique index）。
 */
@RestController
@RequestMapping("/internal/orchestrator/result-versions")
@RequiredArgsConstructor
public class ResultVersionController {

  private final ResultVersionQueryService queryService;
  private final ResultVersionPromoteService promoteService;
  private final ResultVersionMapper resultVersionMapper;

  @GetMapping
  public CommonResponse<List<ResultVersionEntity>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("businessKey") String businessKey,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
    return CommonResponse.success(queryService.listVersions(tenantId, businessKey, limit));
  }

  @GetMapping("/effective")
  public CommonResponse<ResultVersionEntity> effective(
      @RequestParam("tenantId") String tenantId, @RequestParam("businessKey") String businessKey) {
    return CommonResponse.success(queryService.findEffective(tenantId, businessKey).orElse(null));
  }

  @GetMapping("/{id}")
  public CommonResponse<ResultVersionEntity> detail(
      @PathVariable("id") Long id, @RequestParam("tenantId") String tenantId) {
    ResultVersionEntity entity = resultVersionMapper.selectById(tenantId, id);
    if (entity == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
    }
    return CommonResponse.success(entity);
  }

  @PostMapping("/{id}/promote")
  public CommonResponse<ResultVersionEntity> promote(
      @PathVariable("id") Long id, @RequestParam("tenantId") String tenantId) {
    return CommonResponse.success(promoteService.promote(tenantId, id));
  }

  @PostMapping("/{id}/reject")
  public CommonResponse<ResultVersionEntity> reject(
      @PathVariable("id") Long id, @RequestParam("tenantId") String tenantId) {
    return CommonResponse.success(promoteService.rejectPending(tenantId, id));
  }
}
