package com.example.batch.trigger.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantStatusMapper {

    /** 返回租户 status 字段值，租户不存在时返回 null。 */
    String selectStatus(@Param("tenantId") String tenantId);
}
