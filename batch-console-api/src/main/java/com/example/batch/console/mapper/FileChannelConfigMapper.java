package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileChannelConfigMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("channelCode") String channelCode,
                                            @Param("channelType") String channelType,
                                            @Param("enabled") Boolean enabled);
}
