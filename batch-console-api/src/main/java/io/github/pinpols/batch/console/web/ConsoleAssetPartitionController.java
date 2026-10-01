package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.AssetPartitionReadinessResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asset partition console 查询 API。
 *
 * <p>Console 只做租户边界校验与统一响应包装;依赖裁决、EFFECTIVE 分区选择、版本明细由 orchestrator readiness 服务负责，避免前台按
 * result_version / asset_partition 自行拼接语义。
 */
@RestController
@RequestMapping("/api/console/asset-partitions")
@RequiredArgsConstructor
public class ConsoleAssetPartitionController {

  private final ConsoleOrchestratorPort orchestratorProxy;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/readiness")
  public CommonResponse<AssetPartitionReadinessResponse> readiness(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("jobCode") String jobCode,
      @RequestParam("bizDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
    AssetPartitionReadinessResponse resp =
        orchestratorProxy.assetPartitionReadiness(tenantId, jobCode, bizDate);
    return responseFactory.success(resp);
  }
}
