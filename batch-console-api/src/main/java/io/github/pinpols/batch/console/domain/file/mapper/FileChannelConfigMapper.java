package io.github.pinpols.batch.console.domain.file.mapper;

import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpdateParam;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.query.FileChannelConfigQuery;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigReadinessView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileChannelConfigMapper {

  List<FileChannelConfigQueryView> selectByQuery(FileChannelConfigQuery parameters);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("channelCode") String channelCode,
      @Param("channelType") String channelType,
      @Param("enabled") Boolean enabled);

  FileChannelConfigQueryView selectByUniqueKey(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  int upsertFileChannelConfig(@Param("p") FileChannelConfigUpsertParam param);

  FileChannelConfigQueryView selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  int deleteById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int insertFileChannelConfig(@Param("p") FileChannelConfigUpsertParam p);

  int updateFileChannelConfig(@Param("p") FileChannelConfigUpdateParam param);

  /**
   * 租户就绪自检专用:返回渠道的最小完整性字段 (channelCode, channelType, authType, enabled, config_json,
   * target_endpoint)。只读,供 readiness 端点判定凭据 / 端点是否配齐。
   */
  List<FileChannelConfigReadinessView> selectReadinessRows(@Param("tenantId") String tenantId);
}
