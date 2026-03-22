package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FileTemplateConfigMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("templateCode") String templateCode,
                                            @Param("templateType") String templateType,
                                            @Param("enabled") Boolean enabled);

    Map<String, Object> selectSecurityFlagsByTemplateCode(@Param("tenantId") String tenantId,
                                                          @Param("templateCode") String templateCode);
}
