package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityCheckEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-021 数据对账检查结果 mapper。 */
public interface DataQualityCheckMapper {

  int insertWithInstanceReferenceGuard(DataQualityCheckEntity entity);

  default int insert(DataQualityCheckEntity entity) {
    int inserted = insertWithInstanceReferenceGuard(entity);
    if (inserted != 1) {
      throw new IllegalStateException(
          "Data quality check references a missing or different-tenant JobInstance");
    }
    return inserted;
  }

  List<DataQualityCheckEntity> selectByJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);
}
