package com.example.batch.console.service;

import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.support.auth.ConsolePasswordHasher;
import com.example.batch.console.support.auth.ConsoleRoles;
import com.example.batch.console.support.auth.ConsoleSessionRegistry;
import com.example.batch.console.web.response.auth.ConsoleUserAccountResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service("consoleUserAccountManagementService")
@RequiredArgsConstructor
public class ConsoleUserAccountService {

  private final ConsoleUserAccountMapper userAccountMapper;
  private final ConsolePasswordHasher passwordHasher;
  private final ConsoleSessionRegistry sessionRegistry;

  public PageResponse<ConsoleUserAccountResponse> list(
      String tenantId, String keyword, PageRequest pageRequest) {
    List<Map<String, Object>> rows =
        userAccountMapper.selectByQuery(tenantId, keyword, pageRequest);
    long total = userAccountMapper.countByQuery(tenantId, keyword);
    List<ConsoleUserAccountResponse> items = rows.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  public ConsoleUserAccountResponse get(long id) {
    return toResponse(assertExists(id));
  }

  public ConsoleUserAccountResponse update(long id, String displayName, String authoritiesCsv) {
    assertExists(id);
    userAccountMapper.updateProfile(id, displayName, normalizeAuthorities(authoritiesCsv));
    return toResponse(userAccountMapper.selectById(id));
  }

  public void resetPassword(long id, String newPassword) {
    Map<String, Object> account = assertExists(id);
    userAccountMapper.updatePasswordHash(id, passwordHasher.encode(newPassword));
    sessionRegistry.invalidateSession(str(account, "username"), str(account, "tenant_id"));
  }

  public ConsoleUserAccountResponse enable(long id) {
    assertExists(id);
    userAccountMapper.updateEnabled(id, true);
    return toResponse(userAccountMapper.selectById(id));
  }

  public ConsoleUserAccountResponse disable(long id) {
    Map<String, Object> account = assertExists(id);
    userAccountMapper.updateEnabled(id, false);
    sessionRegistry.invalidateSession(str(account, "username"), str(account, "tenant_id"));
    return toResponse(userAccountMapper.selectById(id));
  }

  private Map<String, Object> assertExists(long id) {
    return Guard.requireFound(userAccountMapper.selectById(id), "user account not found: " + id);
  }

  private ConsoleUserAccountResponse toResponse(Map<String, Object> row) {
    return new ConsoleUserAccountResponse(
        row.get("id") instanceof Number n ? n.longValue() : null,
        str(row, "tenant_id"),
        str(row, "username"),
        str(row, "display_name"),
        str(row, "authorities_csv"),
        Boolean.TRUE.equals(row.get("enabled")),
        str(row, "created_at"),
        str(row, "updated_at"));
  }

  private String normalizeAuthorities(String raw) {
    if (raw == null || raw.isBlank()) {
      return ConsoleRoles.USER;
    }
    return raw.trim().toUpperCase();
  }

  private static String str(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? null : String.valueOf(v);
  }
}
