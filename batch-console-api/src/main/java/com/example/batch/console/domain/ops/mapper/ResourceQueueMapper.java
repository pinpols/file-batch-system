package com.example.batch.console.domain.ops.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.param.ResourceQueueUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ResourceQueueMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("queueCode") String queueCode,
      @Param("queueType") String queueType,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("queueCode") String queueCode,
      @Param("queueType") String queueType,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("queueCode") String queueCode);

  int upsertResourceQueue(ResourceQueueUpsertParam param);

  int insert(Map<String, Object> params);

  int update(Map<String, Object> params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  /** 租户就绪自检专用:返回该租户所有(含 disabled)队列 code 集合,供判定 job.queue_code 是否悬空。 */
  List<String> selectQueueCodes(@Param("tenantId") String tenantId);
}
