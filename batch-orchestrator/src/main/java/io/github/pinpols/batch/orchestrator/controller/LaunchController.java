package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
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
  public LaunchResponse launch(@RequestBody LaunchRequest request) {
    if (gracefulShutdown.isDraining()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.orchestrator.draining");
    }
    return launchApplicationService.launch(request);
  }
}
