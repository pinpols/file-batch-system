package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.OrchestratorDrainService;
import io.github.pinpols.batch.orchestrator.application.service.OrchestratorDrainService.DrainStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orchestrator 优雅停机（Drain）管控控制器，基础路径 {@code /internal/orchestrator/drain}。 提供三个端点：{@code GET
 * /status} 查询当前 Drain 状态， {@code POST /enable} 手动开启 Draining，{@code POST /disable} 手动关闭 Draining。
 * 仅限运维人员通过内部网络调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/orchestrator/drain")
@RequiredArgsConstructor
public class OrchestratorDrainController {

  private final OrchestratorDrainService drainService;

  @GetMapping("/status")
  public DrainStatus status() {
    return drainService.status();
  }

  @PostMapping("/enable")
  public DrainStatus enable() {
    return drainService.enable();
  }

  @PostMapping("/disable")
  public DrainStatus disable() {
    return drainService.disable();
  }
}
