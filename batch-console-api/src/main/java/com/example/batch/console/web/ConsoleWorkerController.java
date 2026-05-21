package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ops.ConsoleWorkerApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.request.ops.DrainWorkerRequest;
import com.example.batch.console.web.request.ops.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ops.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ops.ConsoleWorkerRegistryResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制台 Worker 运维 REST：排空、强制下线、已认领任务查询。 */
@RestController
@Validated
@RequestMapping("/api/console/workers")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleWorkerController {

  private final ConsoleWorkerApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** Worker 优雅排空。 */
  @PostMapping("/{workerCode}/drain")
  public CommonResponse<ConsoleWorkerRegistryResponse> drain(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String workerCode,
      @Valid @RequestBody DrainWorkerRequest request) {
    return responseFactory.success(applicationService.drain(workerCode, request, idempotencyKey));
  }

  /** Worker 强制下线。 */
  @PostMapping("/{workerCode}/force-offline")
  public CommonResponse<ConsoleWorkerRegistryResponse> forceOffline(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String workerCode,
      @Valid @RequestBody ForceOfflineWorkerRequest request) {
    return responseFactory.success(
        applicationService.forceOffline(workerCode, request, idempotencyKey));
  }

  /** 立即接管 Worker 在途任务并退役。 */
  @PostMapping("/{workerCode}/takeover")
  public CommonResponse<ConsoleWorkerRegistryResponse> takeover(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String workerCode,
      @Valid @RequestBody ForceOfflineWorkerRequest request) {
    return responseFactory.success(
        applicationService.takeover(workerCode, request, idempotencyKey));
  }

  /** 查询 Worker 当前已认领任务。 */
  @GetMapping("/{workerCode}/claimed-tasks")
  public CommonResponse<List<ConsoleWorkerClaimedTaskResponse>> claimedTasks(
      @PathVariable String workerCode, @RequestParam String tenantId) {
    return responseFactory.success(applicationService.claimedTasks(tenantId, workerCode));
  }

  /** 预热 Worker：提前建立连接池/缓存，为接收任务做准备。 */
  @PostMapping("/{workerCode}/warmup")
  public CommonResponse<ConsoleWorkerRegistryResponse> warmup(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @PathVariable String workerCode,
      @RequestParam String tenantId) {
    return responseFactory.success(applicationService.warmup(workerCode, tenantId, idempotencyKey));
  }
}
