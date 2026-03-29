package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.support.CalendarBizDateDefinition;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DefaultLaunchAdapterService implements LaunchAdapterService {

    private final CalendarBizDateResolver calendarBizDateResolver;

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
    public LaunchRequest fromScheduledTrigger(ScheduledTriggerCommand command, CalendarBizDateDefinition calendar) {
        var descriptor = command.descriptor();
        ZoneId zoneId = StringUtils.hasText(descriptor.getTimezone())
                ? ZoneId.of(descriptor.getTimezone())
                : ZoneId.systemDefault();
        LocalDate bizDate = calendarBizDateResolver.resolve(command.fireTime(), zoneId, calendar);
        TriggerType triggerType = command.triggerType() == null ? TriggerType.SCHEDULED : command.triggerType();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scheduleType", descriptor.getScheduleType());
        params.put("scheduleExpression", descriptor.getScheduleExpression());
        params.put("triggerMode", descriptor.getTriggerMode());
        params.put("calendarCode", descriptor.getCalendarCode());
        params.put("catchUpPolicy", descriptor.getCatchUpPolicy());
        params.put("scheduledAt", command.fireTime().toString());
        params.put("catchUp", TriggerType.CATCH_UP == triggerType);
        params.put(
                "catchUpApprovalRequired",
                CatchUpPolicyType.MANUAL_APPROVAL.code().equalsIgnoreCase(descriptor.getCatchUpPolicy())
        );
        return new LaunchRequest(
                descriptor.getTenantId(),
                descriptor.getJobCode(),
                bizDate,
                triggerType,
                command.requestId(),
                command.traceId(),
                params
        );
    }

    @Override
    public TriggerType resolveTriggerType(TriggerLaunchCommand command) {
        return command.request().getTriggerType() == null ? TriggerType.API : command.request().getTriggerType();
    }
}
