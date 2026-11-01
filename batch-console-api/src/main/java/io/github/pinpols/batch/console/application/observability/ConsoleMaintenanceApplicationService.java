package io.github.pinpols.batch.console.application.observability;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.request.system.UpdateMaintenanceRequest;
import io.github.pinpols.batch.console.application.contract.response.MaintenanceStatusResponse;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 维护状态查询和管理员更新用例。 */
@Service
@RequiredArgsConstructor
public class ConsoleMaintenanceApplicationService {
  private final MaintenanceStateHolder stateHolder;
  private final ConsoleRequestMetadataResolver metadataResolver;

  public MaintenanceStatusResponse status() {
    return toResponse(stateHolder.current());
  }

  public MaintenanceStatusResponse update(UpdateMaintenanceRequest request) {
    List<String> affected =
        request.getAffectedServices() == null ? List.of() : request.getAffectedServices();
    MaintenanceState next = new MaintenanceState(
        request.isEnabled(),
        request.isReadOnly(),
        request.getMessage(),
        request.getEtaAt(),
        request.getStartsAt(),
        affected);
    MaintenanceState applied =
        stateHolder.update(next, metadataResolver.current().operatorId());
    return toResponse(applied);
  }

  private static MaintenanceStatusResponse toResponse(MaintenanceState state) {
    return new MaintenanceStatusResponse(
        state.enabled(),
        state.readOnly(),
        state.message(),
        state.etaAt() != null ? state.etaAt().toString() : null,
        EmptyChecks.isNull(state.startsAt()) ? null : state.startsAt().toString(),
        state.active(Instant.now()),
        state.affectedServices(),
        state.version(),
        EmptyChecks.isNull(state.updatedAt()) ? null : state.updatedAt().toString());
  }
}
