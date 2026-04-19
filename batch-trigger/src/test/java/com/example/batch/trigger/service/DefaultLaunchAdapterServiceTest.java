package com.example.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.support.CalendarBizDateDefinition;
import com.example.batch.trigger.support.TriggerDescriptor;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultLaunchAdapterServiceTest {

  private static final BatchTimezoneProvider TIMEZONE_PROVIDER =
      new BatchTimezoneProvider(new BatchTimezoneProperties());

  private final DefaultLaunchAdapterService service =
      new DefaultLaunchAdapterService(
          new CalendarBizDateResolver(TIMEZONE_PROVIDER), TIMEZONE_PROVIDER);

  @Test
  void shouldBuildApiLaunchRequestFromControllerPayload() {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setJobCode("IMPORT_JOB");
    request.setBizDate(LocalDate.of(2026, 3, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("source", "api"));

    LaunchRequest launchRequest =
        service.fromApiRequest(
            new TriggerLaunchCommand(request, "idem-001", "req-001", "trace-001"));

    assertThat(launchRequest.tenantId()).isEqualTo("t1");
    assertThat(launchRequest.jobCode()).isEqualTo("IMPORT_JOB");
    assertThat(launchRequest.bizDate()).isEqualTo(LocalDate.of(2026, 3, 27));
    assertThat(launchRequest.triggerType()).isEqualTo(TriggerType.API);
    assertThat(launchRequest.requestId()).isEqualTo("req-001");
    assertThat(launchRequest.traceId()).isEqualTo("trace-001");
    assertThat(launchRequest.params()).containsEntry("source", "api");
  }

  @Test
  void shouldDeriveBizDateAndCatchUpMetadataFromScheduledTrigger() {
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setJobCode("EXPORT_JOB");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 15 0 * * ?");
    descriptor.setTimezone("Asia/Shanghai");
    descriptor.setTriggerMode("MIXED");
    descriptor.setCalendarCode("BIZ_CAL");
    descriptor.setCatchUpPolicy(CatchUpPolicyType.MANUAL_APPROVAL.code());

    Instant fireTime = Instant.parse("2026-03-27T16:30:00Z");
    CalendarBizDateDefinition calendar =
        new CalendarBizDateDefinition(
            "Asia/Shanghai", LocalTime.of(6, 0), "SKIP", Set.of(), Set.of());
    LaunchRequest launchRequest =
        service.fromScheduledTrigger(
            new ScheduledTriggerCommand(
                descriptor, fireTime, TriggerType.CATCH_UP, "req-002", "trace-002"),
            calendar);

    assertThat(launchRequest.bizDate()).isEqualTo(LocalDate.of(2026, 3, 27));
    assertThat(launchRequest.triggerType()).isEqualTo(TriggerType.CATCH_UP);
    assertThat(launchRequest.params())
        .containsEntry("scheduleType", "CRON")
        .containsEntry("scheduleExpression", "0 15 0 * * ?")
        .containsEntry("triggerMode", "MIXED")
        .containsEntry("calendarCode", "BIZ_CAL")
        .containsEntry("catchUpPolicy", CatchUpPolicyType.MANUAL_APPROVAL.code())
        .containsEntry("catchUp", true)
        .containsEntry("catchUpApprovalRequired", true)
        .containsEntry("scheduledAt", fireTime.toString());
  }

  @Test
  void shouldDefaultScheduledTriggerTypeWhenCommandTriggerTypeIsMissing() {
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setJobCode("DISPATCH_JOB");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 * * * ?");
    descriptor.setTimezone("UTC");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCatchUpPolicy(CatchUpPolicyType.NONE.code());

    LaunchRequest launchRequest =
        service.fromScheduledTrigger(
            new ScheduledTriggerCommand(
                descriptor, Instant.parse("2026-03-27T08:00:00Z"), null, "req-003", "trace-003"),
            null);

    assertThat(launchRequest.triggerType()).isEqualTo(TriggerType.SCHEDULED);
    assertThat(launchRequest.params())
        .containsEntry("catchUp", false)
        .containsEntry("catchUpApprovalRequired", false);
  }
}
