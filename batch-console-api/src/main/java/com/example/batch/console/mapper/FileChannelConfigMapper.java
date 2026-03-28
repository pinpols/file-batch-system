package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileChannelConfigMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("channelCode") String channelCode,
                                            @Param("channelType") String channelType,
                                            @Param("enabled") Boolean enabled);

    Map<String, Object> selectByUniqueKey(@Param("tenantId") String tenantId,
                                          @Param("channelCode") String channelCode);

    int upsertFileChannelConfig(@Param("tenantId") String tenantId,
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
}
