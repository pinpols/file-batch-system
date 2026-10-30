package io.github.pinpols.batch.trigger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.List;
import java.util.Objects;

/** 验证补跑冻结载荷；请求 ID 和追踪 ID 可随重试变化。 */
final class TriggerCatchUpIdentity {
  private TriggerCatchUpIdentity() {}

  static void requireMatchingIfCatchUp(TriggerRequestEntity existing, LaunchRequest incoming) {
    if (incoming.triggerType() != TriggerType.CATCH_UP
        && !TriggerType.CATCH_UP.code().equals(existing.getTriggerType())) return;
    try {
      JsonNode stored = JsonUtils.fromJsonStrict(existing.getLaunchPayload(), JsonNode.class);
      JsonNode candidate = JsonUtils.fromJsonStrict(JsonUtils.toJson(incoming), JsonNode.class);
      if (!(stored instanceof ObjectNode storedObject)
          || !(candidate instanceof ObjectNode candidateObject)
          || !Objects.equals(existing.getTenantId(), incoming.tenantId())
          || !Objects.equals(existing.getWorkflowDefinitionId(), incoming.workflowDefinitionId())
          || !Objects.equals(existing.getBizDate(), incoming.bizDate())
          || !Objects.equals(existing.getTriggerType(), incoming.triggerType().code())) {
        throw conflict();
      }
      storedObject.remove(List.of("requestId", "traceId"));
      candidateObject.remove(List.of("requestId", "traceId"));
      if (!storedObject.equals(candidateObject)) throw conflict();
    } catch (BizException expected) {
      throw expected;
    } catch (RuntimeException invalidSnapshot) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.conflict", invalidSnapshot);
    }
  }

  private static BizException conflict() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.common.conflict");
  }
}
