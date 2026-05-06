package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.CalendarDependencyEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-023 calendar_dependency MyBatis 映射。 */
public interface CalendarDependencyMapper {

  int insert(CalendarDependencyEntity record);

  /** 找指定 downstream 的所有 enabled 依赖（通常 1-3 条），open scheduler 决策用。 */
  List<CalendarDependencyEntity> selectEnabledByDownstream(
      @Param("tenantId") String tenantId, @Param("downstreamCode") String downstreamCode);

  /** 用于环检查 / 列表展示。 */
  List<CalendarDependencyEntity> selectAllByTenant(@Param("tenantId") String tenantId);
}
