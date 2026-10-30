package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowInsertParam;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpsertParam;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchWindowMapper {

  List<BatchWindowRow> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("windowCode") String windowCode,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("windowCode") String windowCode,
      @Param("enabled") Boolean enabled);

  BatchWindowRow selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  BatchWindowRow selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("windowCode") String windowCode);

  int upsertBatchWindow(BatchWindowUpsertParam param);

  int insert(BatchWindowInsertParam params);

  int update(BatchWindowUpdateParam params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}
