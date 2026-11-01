package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleEventCatalogService;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleEventTopicResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleEventTypeResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 事件总线目录:暴露系统可订阅事件类型与 Kafka Topic 映射。 */
@RestController
@RequestMapping("/api/console/event-catalog")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ConsoleEventCatalogController {

  private final ConsoleResponseFactory responseFactory;
  private final ConsoleEventCatalogService catalogService;

  @GetMapping("/event-types")
  public CommonResponse<List<ConsoleEventTypeResponse>> eventTypes() {
    return responseFactory.success(catalogService.eventTypes(LocaleContextHolder.getLocale()));
  }

  @GetMapping("/topics")
  public CommonResponse<List<ConsoleEventTopicResponse>> topics() {
    return responseFactory.success(catalogService.topics(LocaleContextHolder.getLocale()));
  }
}
