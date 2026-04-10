package com.example.batch.console.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ConfigApprovalMapper {

    Map<String, Object> selectLatestByRelease(@Param("tenantId") String tenantId,
                                               @Param("releaseId") Long releaseId);

    Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    int insert(Map<String, Object> params);

    int approve(Map<String, Object> params);

    int reject(Map<String, Object> params);
}
