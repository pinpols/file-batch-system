package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警事件内部接收控制器，基础路径 {@code /internal/alerts}。 提供单一端点 {@code POST /internal/alerts}，供内部服务推送告警事件， 委托
 * {@link AlertEventService} 处理后续分发。
 * 仅限内部网络调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/alerts")
@RequiredArgsConstructor
public class AlertInternalController {

  private final AlertEventService alertEventService;

  @PostMapping
  public void emit(@RequestBody AlertEmitRequest request) {
    AlertEmitCommand command = AlertEmitCommand.builder()
        .tenantId(request.tenantId())
        .serviceName(request.serviceName())
        .alertType(request.alertType())
        .severity(request.severity())
        .title(request.title())
        .detailJson(request.detailJson())
        .resourceKey(request.resourceKey())
        .traceId(request.traceId())
        .build();
    alertEventService.emit(command);
  }
}
