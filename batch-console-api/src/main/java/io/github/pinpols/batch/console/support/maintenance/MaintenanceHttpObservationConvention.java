package io.github.pinpols.batch.console.support.maintenance;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/** 为全部 HTTP 请求提供固定的维护标签集合，防止同名 Prometheus 指标的标签结构冲突。 */
@Component
public class MaintenanceHttpObservationConvention
    extends DefaultServerRequestObservationConvention {

  public static final String PLANNED_ATTRIBUTE =
      MaintenanceHttpObservationConvention.class.getName() + ".planned";

  @Override
  public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
    boolean planned = EmptyChecks.isNotNull(context.getCarrier())
        && Boolean.TRUE.equals(context.getCarrier().getAttribute(PLANNED_ATTRIBUTE));
    KeyValues standard = super.getLowCardinalityKeyValues(context);
    return standard.and("maintenance", planned ? "planned" : "none");
  }
}
