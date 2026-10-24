package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.service.BatchDayOperateCommand;
import io.github.pinpols.batch.orchestrator.service.BatchDayOperationService;
import io.github.pinpols.batch.orchestrator.service.BatchDayOperationService.BatchDayOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Independent Orchestrator process that races manual day dispositions against admission/settling. */
public final class BatchDayOperationChildProcess {

  private BatchDayOperationChildProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "expected config path, tenant id, calendar code, and date:operation list");
    }
    Properties properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    String[] applicationArgs = properties.stringPropertyNames().stream()
        .map(key -> "--" + key + "=" + properties.getProperty(key))
        .toArray(String[]::new);
    try (ConfigurableApplicationContext application = new SpringApplicationBuilder(
            BatchOrchestratorApplication.class)
        .profiles("test")
        .web(WebApplicationType.NONE)
        .run(applicationArgs)) {
      BatchDayOperationService service = application.getBean(BatchDayOperationService.class);
      for (String operationSpec : args[3].split(",")) {
        String[] fields = operationSpec.split(":", 2);
        if (fields.length != 2) {
          throw new IllegalArgumentException("invalid date:operation: " + operationSpec);
        }
        LocalDate bizDate = LocalDate.parse(fields[0]);
        BatchDayOperation action = BatchDayOperation.valueOf(fields[1]);
        try {
          service.operate(BatchDayOperateCommand.builder()
              .tenantId(args[1])
              .calendarCode(args[2])
              .bizDate(bizDate)
              .action(action)
              .operatorId("r11-multiprocess-pressure")
              .reason("exercise mixed admission and settlement contention")
              .build());
          System.out.printf("operation completed: date=%s action=%s%n", bizDate, action);
        } catch (BizException failure) {
          if (failure.getCode() != ResultCode.STATE_CONFLICT) {
            throw failure;
          }
          System.out.printf(
              "operation blocked by concurrent terminal state or accepted work: date=%s action=%s key=%s%n",
              bizDate,
              action,
              failure.getMessageKey());
        }
      }
    }
  }
}
