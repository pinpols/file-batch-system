package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** P2 cost profile 热表聚合查询。 */
public interface CapacityProfileMapper {

  List<CapacityProfileRow> selectTenantProfile(
      @Param("tenantId") String tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("limit") int limit);

  List<CapacityProfileRow> selectJobProfile(
      @Param("tenantId") String tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("limit") int limit);

  List<CapacityProfileRow> selectWorkerProfile(
      @Param("tenantId") String tenantId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("limit") int limit);
}
