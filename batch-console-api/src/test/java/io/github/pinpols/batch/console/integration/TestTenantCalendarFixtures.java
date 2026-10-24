package io.github.pinpols.batch.console.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** Test fixture for tenants that create Jobs without an explicit business calendar. */
final class TestTenantCalendarFixtures {

  private TestTenantCalendarFixtures() {}

  static void ensureDefaultCalendar(JdbcTemplate jdbcTemplate, String... tenantIds) {
    for (String tenantId : tenantIds) {
      jdbcTemplate.update(
          "insert into batch.business_calendar"
              + " (tenant_id, calendar_code, calendar_name, timezone, enabled)"
              + " values (?, 'default_calendar', 'Default calendar', 'Asia/Shanghai', true)"
              + " on conflict (tenant_id, calendar_code) do nothing",
          tenantId);
    }
  }
}
