package io.github.pinpols.batch.trigger.integration;

import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Task/分区状态，再决定是否允许重放。 */
public final class OccurrencePlannerCrashProcess {

  private OccurrencePlannerCrashProcess() {}

  public static void main(String[] args) throws Exception {
    Path propertiesPath = Path.of(args[0]);
    Path readyMarker = Path.of(args[1]);
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(propertiesPath)) {
      properties.load(input);
    }

    Map<String, Object> applicationProperties = new HashMap<>();
    properties
        .stringPropertyNames()
        .forEach(key -> applicationProperties.put(key, properties.getProperty(key)));
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles("test");
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("crash-process-test", applicationProperties));

    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
            BatchTriggerApplication.class)
        .environment(environment)
        .web(WebApplicationType.NONE)
        .run()) {
      Files.writeString(readyMarker, Long.toString(ProcessHandle.current().pid()));
      Instant scanAt = Instant.parse(args[2]);
      Instant firstFireTime = Instant.parse(args[3]);
      String tenantId = args[4];
      long workflowDefinitionId = Long.parseLong(args[5]);
      String workflowCode = args[6];
      String calendarCode = args[7];

      TriggerDescriptor descriptor = new TriggerDescriptor();
      descriptor.setTenantId(tenantId);
      descriptor.setWorkflowDefinitionId(workflowDefinitionId);
      descriptor.setWorkflowCode(workflowCode);
      descriptor.setWorkflowVersion(1);
      descriptor.setScheduleType("CRON");
      descriptor.setScheduleExpression("0 0/1 * * * ?");
      descriptor.setTimezone("UTC");
      descriptor.setTriggerMode("SCHEDULED");
      descriptor.setCalendarCode(calendarCode);
      descriptor.setEnabled(true);
      descriptor.setWorkflowEnabled(true);
      Trigger trigger = TriggerBuilder.newTrigger()
          .withIdentity(workflowCode, "crash-process")
          .startAt(Date.from(firstFireTime))
          .withSchedule(
              SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).repeatForever())
          .build();
      context
          .getBean(TriggerScheduledOccurrencePlanner.class)
          .materialize(descriptor, trigger, scanAt);
    }
  }
}
