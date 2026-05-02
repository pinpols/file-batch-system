package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警事件内部接收控制器，基础路径 {@code /internal/alerts}。 提供单一端点 {@code POST /internal/alerts}，供内部服务推送告警事件， 委托
 * {@link com.example.batch.orchestrator.application.service.AlertEventService} 处理后续分发。
 * 仅限内部网络调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/alerts")
@RequiredArgsConstructor
public class AlertInternalController {

  private final AlertEventService alertEventService;

  @PostMapping
  public void emit(@RequestBody AlertEmitRequest request) {
    alertEventService.emit(request);
  }
}
