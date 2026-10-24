package io.github.pinpols.batch.console.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** 破坏性租户清理脚本仅在此测试类自有的一次性数据库上运行。 */
final class TestTenantCalendarFixtures {

  private TestTenantCalendarFixtures() {}

  static void ensureDefaultCalendar(JdbcTemplate jdbcTemplate, String... tenantIds) {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
        values (?, 'default_calendar', 'Default calendar', 'Asia/Shanghai', true)\s\
        on conflict (tenant_id, calendar_code) do nothing\
        """;

    for (String tenantId : tenantIds) {
      jdbcTemplate.update(insertBusinessCalendarSql, tenantId);
    }
  }
}
