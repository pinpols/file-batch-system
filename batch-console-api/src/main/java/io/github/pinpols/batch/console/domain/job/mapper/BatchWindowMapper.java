package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface BatchWindowMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("windowCode") String windowCode,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("windowCode") String windowCode,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("windowCode") String windowCode);

  int upsertBatchWindow(BatchWindowUpsertParam param);

  int insert(Map<String, Object> params);

  int update(Map<String, Object> params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}
