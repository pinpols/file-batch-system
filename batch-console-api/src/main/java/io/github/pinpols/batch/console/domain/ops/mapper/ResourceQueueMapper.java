package io.github.pinpols.batch.console.domain.ops.mapper;

import io.github.pinpols.batch.console.domain.ops.query.ResourceQueueQuery;
import io.github.pinpols.batch.console.domain.ops.view.ResourceQueueRow;
import io.github.pinpols.batch.console.domain.param.ResourceQueueInsertParam;
import io.github.pinpols.batch.console.domain.param.ResourceQueueUpdateParam;
import io.github.pinpols.batch.console.domain.param.ResourceQueueUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ResourceQueueMapper {

  List<ResourceQueueRow> selectByQuery(ResourceQueueQuery query);

  long countByQuery(ResourceQueueQuery query);

  ResourceQueueRow selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  ResourceQueueRow selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("queueCode") String queueCode);

  int upsertResourceQueue(ResourceQueueUpsertParam param);

  int insert(ResourceQueueInsertParam params);

  int update(ResourceQueueUpdateParam params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  /** 租户就绪自检专用:返回该租户所有(含 disabled)队列 code 集合,供判定 job.queue_code 是否悬空。 */
  List<String> selectQueueCodes(@Param("tenantId") String tenantId);
}
