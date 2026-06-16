package com.example.batch.console.domain.rbac.mapper;

import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
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

  /** 改密 / reset 时同步设置 must_change_password 标志(true=要求下次登录改密,false=清除)。 */
  int updatePasswordHashAndMustChange(
      @Param("id") long id,
      @Param("passwordHash") String passwordHash,
      @Param("mustChangePassword") boolean mustChangePassword);

  /** 启动期默认密码守护:返回内置系统账号的 (username, password_hash, must_change_password)。 */
  List<ConsoleUserAccountEntity> selectBuiltinSystemAccounts(
      @Param("usernames") List<String> usernames);

  int deleteById(@Param("id") long id);
}
