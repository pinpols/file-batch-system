package com.example.batch.trigger.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JdbcTriggerDefinitionLoader implements TriggerDefinitionLoader {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<TriggerDescriptor> loadAll() {
        String sql = """
                select jd.tenant_id,
                       jd.job_code,
                       jd.schedule_type,
                       jd.schedule_expr,
                       jd.timezone,
                       jd.trigger_mode,
                       jd.calendar_code,
                       coalesce(bc.catch_up_policy, 'NONE') as catch_up_policy,
                       coalesce(bc.catch_up_max_days, 0) as catch_up_max_days,
                       jd.enabled
                from batch.job_definition jd
                left join batch.business_calendar bc
                  on bc.tenant_id = jd.tenant_id
                 and bc.calendar_code = jd.calendar_code
                 and bc.enabled = true
                where jd.enabled = true
                  and jd.schedule_type = 'CRON'
                  and jd.trigger_mode in ('SCHEDULED', 'MIXED')
                order by jd.tenant_id, jd.job_code
                """;
        return jdbcTemplate.query(sql, this::mapDescriptor);
    }

    @Override
    public TriggerDescriptor loadByJobCode(String tenantId, String jobCode) {
        String sql = """
                select jd.tenant_id,
                       jd.job_code,
                       jd.schedule_type,
                       jd.schedule_expr,
                       jd.timezone,
                       jd.trigger_mode,
                       jd.calendar_code,
                       coalesce(bc.catch_up_policy, 'NONE') as catch_up_policy,
                       coalesce(bc.catch_up_max_days, 0) as catch_up_max_days,
                       jd.enabled
                from batch.job_definition jd
                left join batch.business_calendar bc
                  on bc.tenant_id = jd.tenant_id
                 and bc.calendar_code = jd.calendar_code
                 and bc.enabled = true
                where jd.tenant_id = ?
                  and jd.job_code = ?
                """;
        return jdbcTemplate.query(sql, this::mapDescriptor, tenantId, jobCode).stream().findFirst().orElse(null);
    }

    private TriggerDescriptor mapDescriptor(ResultSet rs, int rowNum) throws SQLException {
        TriggerDescriptor descriptor = new TriggerDescriptor();
        descriptor.setTenantId(rs.getString("tenant_id"));
        descriptor.setJobCode(rs.getString("job_code"));
        descriptor.setScheduleType(rs.getString("schedule_type"));
        descriptor.setScheduleExpression(rs.getString("schedule_expr"));
        descriptor.setTimezone(rs.getString("timezone"));
        descriptor.setTriggerMode(rs.getString("trigger_mode"));
        descriptor.setCalendarCode(rs.getString("calendar_code"));
        descriptor.setCatchUpPolicy(rs.getString("catch_up_policy"));
        descriptor.setCatchUpMaxDays(rs.getInt("catch_up_max_days"));
        descriptor.setEnabled(rs.getBoolean("enabled"));
        return descriptor;
    }
}
