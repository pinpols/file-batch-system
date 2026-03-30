package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.health.StartupSelfCheck;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test: orchestrator startup + Flyway migration schema validation chain.
 *
 * <p>Verifies that after Spring context starts (which triggers Flyway migration via
 * {@link org.flywaydb.core.Flyway}) the following are all satisfied:
 * <ul>
 *   <li>{@code batch} and {@code quartz} schemas exist</li>
 *   <li>{@code batch.batch_day_instance} table exists (V31)</li>
 *   <li>{@code batch.business_calendar} columns added by V31 are present</li>
 *   <li>All Quartz tables exist in the {@code quartz} schema</li>
 *   <li>{@link StartupSelfCheck} bean is present in the context (i.e. self-check is wired)</li>
 * </ul>
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StartupSelfCheckIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StartupSelfCheck startupSelfCheck;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startupSelfCheckBeanIsPresent() {
        assertThat(startupSelfCheck).isNotNull();
    }

    @Test
    void batchSchemaExistsAfterMigration() {
        Long cnt = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'batch'",
                Long.class);
        assertThat(cnt).isEqualTo(1L);
    }

    @Test
    void quartzSchemaExistsAfterMigration() {
        Long cnt = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'quartz'",
                Long.class);
        assertThat(cnt).isEqualTo(1L);
    }

    @Test
    void batchDayInstanceTableExistsAfterV31Migration() {
        Long cnt = jdbcTemplate.queryForObject(
                """
                select count(*) from information_schema.tables
                where table_schema = 'batch' and table_name = 'batch_day_instance'
                """,
                Long.class);
        assertThat(cnt).isEqualTo(1L);
    }

    @Test
    void businessCalendarV31ColumnsExistAfterMigration() {
        for (String column : new String[]{"cutoff_time", "late_arrival_tolerance_min", "sla_offset_min"}) {
            Long cnt = jdbcTemplate.queryForObject(
                    """
                    select count(*) from information_schema.columns
                    where table_schema = 'batch'
                      and table_name = 'business_calendar'
                      and column_name = ?
                    """,
                    Long.class,
                    column);
            assertThat(cnt).as("column business_calendar.%s should exist", column).isEqualTo(1L);
        }
    }

    @Test
    void allQuartzTablesExistAfterMigration() {
        for (String table : new String[]{
                "qrtz_job_details", "qrtz_triggers", "qrtz_simple_triggers",
                "qrtz_cron_triggers", "qrtz_simprop_triggers", "qrtz_blob_triggers",
                "qrtz_calendars", "qrtz_paused_trigger_grps", "qrtz_fired_triggers",
                "qrtz_scheduler_state", "qrtz_locks"}) {
            Long cnt = jdbcTemplate.queryForObject(
                    """
                    select count(*) from information_schema.tables
                    where table_schema = 'quartz' and table_name = ?
                    """,
                    Long.class,
                    table);
            assertThat(cnt).as("quartz table %s should exist", table).isEqualTo(1L);
        }
    }
}
