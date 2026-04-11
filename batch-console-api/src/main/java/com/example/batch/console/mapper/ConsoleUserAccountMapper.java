package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface ConsoleUserAccountMapper {

    List<Map<String, Object>> selectByQuery(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("pageRequest") PageRequest pageRequest);

    long countByQuery(@Param("tenantId") String tenantId, @Param("keyword") String keyword);

    Map<String, Object> selectById(@Param("id") long id);

    Map<String, Object> selectByUsername(@Param("username") String username);

    int insert(
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash,
            @Param("authoritiesCsv") String authoritiesCsv,
            @Param("createdBy") String createdBy);

    int updateProfile(
            @Param("id") long id,
            @Param("displayName") String displayName,
            @Param("authoritiesCsv") String authoritiesCsv);

    int updatePasswordHash(@Param("id") long id, @Param("passwordHash") String passwordHash);

    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    int deleteById(@Param("id") long id);
}
