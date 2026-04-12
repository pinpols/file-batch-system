package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/orchestrator/drain")
@RequiredArgsConstructor
public class OrchestratorDrainController {

  private final OrchestratorGracefulShutdown gracefulShutdown;

  @GetMapping("/status")
  public Map<String, Object> status() {
    return gracefulShutdown.status();
  }

  @PostMapping("/enable")
  public Map<String, Object> enable() {
    gracefulShutdown.startDraining("manual-enable");
    return gracefulShutdown.status();
  }

  @PostMapping("/disable")
  public Map<String, Object> disable() {
    gracefulShutdown.stopDraining("manual-disable");
    return gracefulShutdown.status();
  }
}
