package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度器任务启动内部控制器，基础路径 {@code /internal/orchestrator}。 提供 {@code POST /internal/orchestrator/launch}
 * 端点，用于触发任务实例启动； 若 Orchestrator 正处于 Draining（优雅停机）状态，则拒绝请求并返回 {@code STATE_CONFLICT} 错误。
 * 仅限内部网络调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/orchestrator")
@RequiredArgsConstructor
public class LaunchController {

  private final LaunchApplicationService launchApplicationService;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @PostMapping("/launch")
  public LaunchResponse launch(@RequestBody LaunchRequest request, HttpServletRequest httpRequest) {
    if (gracefulShutdown.isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.orchestrator.draining");
    }
    // 租户边界守卫(与 Worker/Task 控制器一致):租户 API-Key 路径下,以 filter 解析出的真实租户
    // 为准,与 body 声明对账,不一致即拒。legacy X-Internal-Secret 路径无解析租户时透传 body。
    return launchApplicationService.launch(withGuardedTenant(request, httpRequest));
  }

  private static LaunchRequest withGuardedTenant(
      LaunchRequest request, HttpServletRequest httpRequest) {
    String declared = request == null ? null : request.tenantId();
    String resolved = InternalRequestTenantGuard.resolveTenant(httpRequest, declared);
    if (request == null || Objects.equals(resolved, declared)) {
      return request;
    }
    return request.toBuilder().tenantId(resolved).build();
  }
}
