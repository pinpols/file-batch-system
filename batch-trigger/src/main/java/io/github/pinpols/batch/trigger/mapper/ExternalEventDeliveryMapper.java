package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.domain.ExternalEventTargetStatus;
import io.github.pinpols.batch.trigger.domain.entity.ExternalEventDeliveryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ExternalEventDeliveryMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  int insert(ExternalEventDeliveryEntity entity);

  ExternalEventDeliveryEntity selectByIdentityForUpdate(
      @Param("tenantId") String tenantId,
      @Param("eventSource") String eventSource,
      @Param("eventId") String eventId);

  int insertAlias(
      @Param("tenantId") String tenantId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("deliveryId") Long deliveryId);

  Long selectAliasDeliveryId(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

  boolean containsWorkflowTarget(
      @Param("tenantId") String tenantId,
      @Param("eventSource") String eventSource,
      @Param("eventId") String eventId,
      @Param("workflowDefinitionId") Long workflowDefinitionId);

  ExternalEventDeliveryEntity selectByIdentity(
      @Param("tenantId") String tenantId,
      @Param("eventSource") String eventSource,
      @Param("eventId") String eventId);

  ExternalEventDeliveryEntity selectByAlias(
      @Param("tenantId") String tenantId,
      @Param("eventSource") String eventSource,
      @Param("idempotencyKey") String idempotencyKey);

  List<ExternalEventTargetStatus> selectTargetStatuses(
      @Param("tenantId") String tenantId,
      @Param("eventSource") String eventSource,
      @Param("eventId") String eventId);
}
