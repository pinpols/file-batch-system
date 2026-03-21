package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultLaunchAdapterService implements LaunchAdapterService {

    @Override
    public LaunchRequest fromApiRequest(TriggerLaunchCommand command) {
        var request = command.request();
        return new LaunchRequest(
                request.getTenantId(),
                request.getJobCode(),
                request.getBizDate(),
                resolveTriggerType(command),
                command.requestId(),
                command.traceId(),
                request.getParams()
        );
    }

    @Override
    public LaunchRequest fromScheduledTrigger(ScheduledTriggerCommand command) {
        var descriptor = command.descriptor();
        ZoneId zoneId = ZoneId.of(descriptor.getTimezone());
        LocalDate bizDate = command.fireTime().atZone(zoneId).toLocalDate();
        TriggerType triggerType = command.triggerType() == null ? TriggerType.SCHEDULED : command.triggerType();
        return new LaunchRequest(
                descriptor.getTenantId(),
                descriptor.getJobCode(),
                bizDate,
                triggerType,
                command.requestId(),
                command.traceId(),
                Map.of(
                        "scheduleType", descriptor.getScheduleType(),
                        "scheduleExpression", descriptor.getScheduleExpression(),
                        "triggerMode", descriptor.getTriggerMode(),
                        "calendarCode", descriptor.getCalendarCode(),
                        "catchUpPolicy", descriptor.getCatchUpPolicy(),
                        "scheduledAt", command.fireTime().toString(),
                        "catchUp", TriggerType.CATCH_UP == triggerType,
                        "catchUpApprovalRequired", CatchUpPolicyType.MANUAL_APPROVAL.code().equalsIgnoreCase(descriptor.getCatchUpPolicy())
                )
        );
    }

    @Override
    public TriggerType resolveTriggerType(TriggerLaunchCommand command) {
        return command.request().getTriggerType() == null ? TriggerType.API : command.request().getTriggerType();
    }
}
