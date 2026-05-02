package com.example.batch.console.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.ConsoleUserAccountEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

public interface ConsoleUserAccountMapper {

  /**
   * 认证路径专用：lower(username) ilike 匹配，返回 entity（替代
   * ConsoleUserAccountRepository#findByUsernameIgnoreCase）。
   */
  Optional<ConsoleUserAccountEntity> findByUsernameIgnoreCase(@Param("username") String username);

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
