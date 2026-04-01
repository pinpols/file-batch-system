package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface PipelineDefinitionMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("jobCode") String jobCode,
                                            @Param("pipelineType") String pipelineType,
                                            @Param("enabled") Boolean enabled,
                                            @Param("pageRequest") PageRequest pageRequest);

    long countByQuery(@Param("tenantId") String tenantId,
                      @Param("jobCode") String jobCode,
                      @Param("pipelineType") String pipelineType,
                      @Param("enabled") Boolean enabled);

    Map<String, Object> selectById(@Param("tenantId") String tenantId,
                                   @Param("id") Long id);

    int insert(Map<String, Object> params);

    int update(Map<String, Object> params);

    int toggleEnabled(@Param("tenantId") String tenantId,
                      @Param("id") Long id,
                      @Param("enabled") Boolean enabled);

    int deleteByTenantAndId(@Param("tenantId") String tenantId,
                            @Param("id") Long id);
}
