package com.example.batch.console.support;

import com.example.batch.console.domain.ConsoleUserAccountEntity;
import com.example.batch.console.repository.ConsoleUserAccountRepository;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 控制台账号查询：通过 Spring Data JDBC 从平台库读取账号、密码哈希和角色列表。 */
@Service
public class ConsoleUserAccountService {

  private final ConsoleUserAccountRepository repository;

  public ConsoleUserAccountService(ConsoleUserAccountRepository repository) {
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
        entity.isEnabled());
  }

  private Set<String> parseAuthorities(String raw) {
    if (!StringUtils.hasText(raw)) {
      return Set.of();
    }
    Set<String> authorities = new LinkedHashSet<>();
    Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .map(value -> value.toUpperCase(Locale.ROOT))
        .forEach(authorities::add);
    return authorities;
  }
}
