package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpdateParam;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileChannelConfigMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("channelCode") String channelCode,
      @Param("channelType") String channelType,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("channelCode") String channelCode,
      @Param("channelType") String channelType,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  int upsertFileChannelConfig(@Param("p") FileChannelConfigUpsertParam param);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  int deleteById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int insertFileChannelConfig(@Param("p") FileChannelConfigUpsertParam p);

  int updateFileChannelConfig(@Param("p") FileChannelConfigUpdateParam param);

  /**
   * 租户就绪自检专用:返回渠道的最小完整性字段 (channelCode, channelType, authType, enabled, config_json,
   * target_endpoint)。只读,供 readiness 端点判定凭据 / 端点是否配齐。
   */
  List<Map<String, Object>> selectReadinessRows(@Param("tenantId") String tenantId);
}
