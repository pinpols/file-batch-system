package com.example.batch.console.domain.rbac.support;

import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import com.example.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 控制台账号查询：通过 Spring Data JDBC 从平台库读取账号、密码哈希和角色列表。 */
@Service
public class ConsoleUserAccountServiceSupport {

  private final ConsoleUserAccountMapper repository;

  public ConsoleUserAccountServiceSupport(ConsoleUserAccountMapper repository) {
    this.repository = repository;
  }

  /** 按用户名全局查找账号（用户名全局唯一，租户从账号记录中获取）。 */
  public Optional<ConsoleUserAccount> findByUsername(String username) {
    return repository.findByUsernameIgnoreCase(username).map(this::toAccount);
  }

  private ConsoleUserAccount toAccount(ConsoleUserAccountEntity entity) {
    return new ConsoleUserAccount(
        entity.getTenantId(),
        entity.getUsername(),
        entity.getDisplayName(),
        entity.getPasswordHash(),
        parseAuthorities(entity.getAuthoritiesCsv()),
        entity.isEnabled(),
        entity.isMustChangePassword());
  }

  private Set<String> parseAuthorities(String raw) {
    if (!Texts.hasText(raw)) {
      return Set.of();
    }
    Set<String> authorities = new LinkedHashSet<>();
    Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(Texts::hasText)
        .map(value -> value.toUpperCase(Locale.ROOT))
        .forEach(authorities::add);
    return authorities;
  }
}
