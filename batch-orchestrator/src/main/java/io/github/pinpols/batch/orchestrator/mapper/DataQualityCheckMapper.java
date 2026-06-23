package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityCheckEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-021 数据对账检查结果 mapper。 */
public interface DataQualityCheckMapper {

  int insert(DataQualityCheckEntity entity);

  List<DataQualityCheckEntity> selectByJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);
}
