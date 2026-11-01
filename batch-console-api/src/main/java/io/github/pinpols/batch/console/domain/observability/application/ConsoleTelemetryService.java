package io.github.pinpols.batch.console.domain.observability.application;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.observability.application.contract.request.FrontendTelemetryRequest;
import io.github.pinpols.batch.console.domain.observability.application.contract.request.FrontendTelemetryRequest.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 记录不包含调用方文本的前端事件摘要。 */
@Service
@Slf4j(
    topic = "io.github.pinpols.batch.console.domain.observability.web.ConsoleTelemetryController")
public class ConsoleTelemetryService {

  public void receive(FrontendTelemetryRequest request) {
    for (Event event : request.events()) {
      int propsKeys = EmptyChecks.isNull(event.props()) ? 0 : event.props().size();
      if ("error".equals(event.type())) {
        log.error(
            "[frontend:error] telemetry event received nameLength={} pagePresent={} propsKeys={}",
            event.name().length(),
            EmptyChecks.isNotBlank(event.page()),
            propsKeys);
      } else {
        String eventType =
            switch (event.type()) {
              case "route", "click", "api", "error" -> event.type();
              default -> "custom";
            };
        log.info(
            "[frontend] telemetry event received type={} nameLength={} pagePresent={} propsKeys={}",
            eventType,
            event.name().length(),
            EmptyChecks.isNotBlank(event.page()),
            propsKeys);
      }
    }
  }
}
