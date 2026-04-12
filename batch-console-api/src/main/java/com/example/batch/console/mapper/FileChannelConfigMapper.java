package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.mapper.param.FileChannelConfigUpdateParam;
import com.example.batch.console.mapper.param.FileChannelConfigUpsertParam;
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

  int insertFileChannelConfig(
      @Param("tenantId") String tenantId,
      @Param("channelCode") String channelCode,
      @Param("channelName") String channelName,
      @Param("channelType") String channelType,
      @Param("targetEndpoint") String targetEndpoint,
      @Param("authType") String authType,
      @Param("configJson") String configJson,
      @Param("receiptPolicy") String receiptPolicy,
      @Param("timeoutSeconds") Integer timeoutSeconds,
      @Param("enabled") Boolean enabled,
      @Param("createdBy") String createdBy,
      @Param("updatedBy") String updatedBy);

  int updateFileChannelConfig(@Param("p") FileChannelConfigUpdateParam param);
}
