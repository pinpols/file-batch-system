package io.github.pinpols.batch.orchestrator.application.service.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Compares the selected plugin's declaration across the whole ONLINE Worker group. */
@Service
@RequiredArgsConstructor
public class WorkflowReplayCapabilityService {

  private final WorkerRegistryMapper workerRegistryMapper;

  public Assessment assess(String tenantId, String workerGroup, String pluginId) {
    if (EmptyChecks.isBlank(tenantId) || EmptyChecks.isBlank(workerGroup)) {
      return new Assessment(Status.WORKER_GROUP_NOT_FROZEN, null, 0);
    }
    if (EmptyChecks.isBlank(pluginId)) {
      return new Assessment(Status.PLUGIN_SELECTION_NOT_FROZEN, null, 0);
    }
    List<String> catalogues = workerRegistryMapper.selectReplayCapabilitiesByTenantAndWorkerGroup(
        tenantId, workerGroup, List.of(WorkerRegistryStatus.ONLINE.code()));
    if (EmptyChecks.isEmpty(catalogues)) {
      return new Assessment(Status.NO_ONLINE_WORKERS, null, 0);
    }
    ReplayCapabilityDeclaration agreed = null;
    for (String catalogue : catalogues) {
      List<ReplayCapabilityDeclaration> matches = selectedDeclarations(catalogue, pluginId);
      if (matches.size() != 1) {
        return new Assessment(Status.MISSING_OR_AMBIGUOUS_DECLARATION, null, catalogues.size());
      }
      ReplayCapabilityDeclaration declaration = matches.getFirst();
      if (!declaration.isFullyDeclared()
          || declaration.idempotencyCapability() == IdempotencyCapability.NONE) {
        return new Assessment(Status.REPLAY_SAFETY_UNPROVEN, declaration, catalogues.size());
      }
      if (EmptyChecks.isNotNull(agreed) && !agreed.equals(declaration)) {
        return new Assessment(Status.WORKER_DECLARATIONS_DIFFER, null, catalogues.size());
      }
      agreed = declaration;
    }
    return new Assessment(Status.DECLARED_CONSISTENT, agreed, catalogues.size());
  }

  private List<ReplayCapabilityDeclaration> selectedDeclarations(
      String catalogue, String pluginId) {
    if (EmptyChecks.isBlank(catalogue)) {
      return List.of();
    }
    try {
      List<ReplayCapabilityDeclaration> declarations =
          JsonUtils.fromJson(catalogue, new TypeReference<List<ReplayCapabilityDeclaration>>() {});
      if (EmptyChecks.isNull(declarations)) {
        return List.of();
      }
      return declarations.stream()
          .filter(EmptyChecks::isNotNull)
          .filter(declaration -> pluginId.equals(declaration.pluginId()))
          .toList();
    } catch (IllegalArgumentException invalidCatalogue) {
      // Invalid persisted registration metadata must never make an automatic replay admissible.
      return List.of();
    }
  }

  public enum Status {
    DECLARED_CONSISTENT,
    WORKER_GROUP_NOT_FROZEN,
    PLUGIN_SELECTION_NOT_FROZEN,
    NO_ONLINE_WORKERS,
    MISSING_OR_AMBIGUOUS_DECLARATION,
    REPLAY_SAFETY_UNPROVEN,
    WORKER_DECLARATIONS_DIFFER
  }

  /** A consistent declaration is an input to preview, never an execution admission by itself. */
  public record Assessment(
      Status status, ReplayCapabilityDeclaration declaration, int workerCount) {}
}
