package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGroupExpectationDefinitionUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.query.FileGroupExpectationDefinition;
import io.github.pinpols.batch.orchestrator.domain.query.FileGroupUploadTarget;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.FileGroupExpectationDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;

/** 配置周期性文件组，并在每个业务日开启时生成其预期记录。 */
@Service
public class FileGroupExpectationService {

  private final FileGroupExpectationDefinitionMapper definitionMapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final ExpectedWorkOccurrenceMapper occurrenceMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final PlatformTransactionManager transactionManager;

  public FileGroupExpectationService(
      FileGroupExpectationDefinitionMapper definitionMapper,
      BatchDayInstanceMapper batchDayInstanceMapper,
      ExpectedWorkOccurrenceMapper occurrenceMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      PlatformTransactionManager transactionManager) {
    this.definitionMapper = definitionMapper;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
    this.occurrenceMapper = occurrenceMapper;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.transactionManager = transactionManager;
  }

  public int configure(DefinitionRequest request) {
    validate(request);
    try {
      return RlsTenantContextHolder.runWithTenant(
          request.tenantId(), () -> configureInTransaction(request));
    } catch (DataIntegrityViolationException failure) {
      if (JdbcExceptionSupport.isForeignKeyConstraintViolation(
          failure, "fk_file_group_expectation_workflow_calendar")) {
        throw new OperationFailure(Kind.CONFLICT,
            "Workflow calendar changed while configuring the file-group expectation",
            failure);
      }
      throw failure;
    }
  }

  /** 返回租户内可用于人工文件入站的启用文件组绑定。 */
  public List<FileGroupUploadTarget> listEnabledUploadTargets(String tenantId) {
    if (!valid(tenantId, 64)) {
      throw new IllegalArgumentException("tenant is required");
    }
    return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      return transaction.execute(status -> {
        occurrenceMapper.setTenantContext(tenantId);
        List<FileGroupUploadTarget> targets = definitionMapper.selectEnabledUploadTargets(tenantId);
        Map<String, Long> countByGroup = targets.stream()
            .collect(
                Collectors.groupingBy(FileGroupUploadTarget::fileGroupCode, Collectors.counting()));
        return targets.stream()
            .filter(target -> countByGroup.getOrDefault(target.fileGroupCode(), 0L) == 1L)
            .toList();
      });
    });
  }

  /** 校验上传请求精确匹配唯一启用的文件组到 Workflow 绑定。 */
  public FileGroupUploadTarget requireEnabledUploadTarget(
      String tenantId, String calendarCode, Long workflowDefinitionId, String fileGroupCode) {
    if (!valid(tenantId, 64)
        || !valid(calendarCode, 128)
        || EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || !valid(fileGroupCode, 128)) {
      throw new IllegalArgumentException("tenant, calendar, workflow and file group are required");
    }
    return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      return transaction.execute(status -> {
        occurrenceMapper.setTenantContext(tenantId);
        List<FileGroupUploadTarget> groupTargets =
            definitionMapper.selectEnabledUploadTargetsByGroup(tenantId, fileGroupCode);
        if (groupTargets.size() != 1) {
          throw new OperationFailure(Kind.INVALID_CONFIGURATION,
              "File group must map to exactly one enabled Workflow before manual upload");
        }
        FileGroupUploadTarget target = groupTargets.get(0);
        boolean exactTargetMatches = target.workflowDefinitionId().equals(workflowDefinitionId)
            && target.calendarCode().equals(calendarCode);
        if (!exactTargetMatches) {
          throw new OperationFailure(Kind.INVALID_CONFIGURATION,
              "Manual upload target does not match the enabled file-group binding");
        }
        return target;
      });
    });
  }

  private int configureInTransaction(DefinitionRequest request) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    return transactionTemplate.execute(status -> {
      occurrenceMapper.setTenantContext(request.tenantId());
      WorkflowDefinitionView workflow = workflowDefinitionMapper.selectByTenantAndIdAndEnabled(
          request.tenantId(), request.workflowDefinitionId(), true);
      if (EmptyChecks.isNull(workflow) || !request.calendarCode().equals(workflow.calendarCode())) {
        throw new OperationFailure(Kind.INVALID_CONFIGURATION,
            "File-group expectation calendar must match the workflow calendar");
      }
      FileGroupExpectationDefinitionUpsertParam fileGroupExpectationDefinitionUpsertParam =
          FileGroupExpectationDefinitionUpsertParam.builder()
              .tenantId(request.tenantId())
              .calendarCode(request.calendarCode())
              .workflowDefinitionId(request.workflowDefinitionId())
              .fileGroupCode(request.fileGroupCode())
              .expectedLocalTime(request.expectedLocalTime())
              .dueLocalTime(request.dueLocalTime())
              .enabled(request.enabled())
              .build();
      return definitionMapper.upsert(fileGroupExpectationDefinitionUpsertParam);
    });
  }

  /** 明确只删除周期性定义；已经生成的 occurrence 仍保留以供审计。 */
  public boolean deleteDefinition(
      String tenantId, String calendarCode, Long workflowDefinitionId, String fileGroupCode) {
    boolean fileGroupDefinitionIdentityIsInvalid = !valid(tenantId, 64)
        || !valid(calendarCode, 128)
        || EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || !valid(fileGroupCode, 128);
    if (fileGroupDefinitionIdentityIsInvalid) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId and fileGroupCode are required");
    }
    return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      return transaction.execute(status -> {
        occurrenceMapper.setTenantContext(tenantId);
        return definitionMapper.delete(tenantId, calendarCode, workflowDefinitionId, fileGroupCode)
            == 1;
      });
    });
  }

  /** 在批次日开启事务内调用；重复开启会安全复用唯一标识。 */
  public int materializeForOpenedBusinessDay(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      return transaction.execute(status -> materializeForOpenedBusinessDayInTenant(
          tenantId, calendarCode, bizDate, timezoneSnapshot));
    });
  }

  private int materializeForOpenedBusinessDayInTenant(
      String tenantId, String calendarCode, LocalDate bizDate, String timezoneSnapshot) {
    occurrenceMapper.setTenantContext(tenantId);
    batchDayInstanceMapper.acquireSettlementFence(tenantId, calendarCode, bizDate);
    BatchDayInstanceEntity batchDay =
        batchDayInstanceMapper.selectByTenantCalendarBizDate(tenantId, calendarCode, bizDate);
    if (EmptyChecks.isNotNull(batchDay) && isTerminal(batchDay.dayStatus())) {
      throw new OperationFailure(Kind.CONFLICT,
          "Cannot materialize a file-group expectation for a terminal batch day");
    }
    ZoneId zone = ZoneId.of(timezoneSnapshot);
    int materialized = 0;
    for (FileGroupExpectationDefinition definition :
        definitionMapper.selectEnabledByCalendar(tenantId, calendarCode)) {
      Instant expectedAt = resolve(bizDate, definition.expectedLocalTime(), zone);
      Instant dueAt = EmptyChecks.isNull(definition.dueLocalTime())
          ? null
          : resolve(bizDate, definition.dueLocalTime(), zone);
      ExpectedWorkOccurrenceInsertIfAbsentParam expectedWorkOccurrenceInsertIfAbsentParam =
          ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
              .tenantId(tenantId)
              .calendarCode(calendarCode)
              .bizDate(bizDate)
              .workflowDefinitionId(definition.workflowDefinitionId())
              .sourceType("FILE_GROUP")
              .sourceKey(definition.fileGroupCode())
              .expectedAt(expectedAt)
              .dueAt(dueAt)
              .build();
      materialized += occurrenceMapper.insertIfAbsent(expectedWorkOccurrenceInsertIfAbsentParam);
    }
    return materialized;
  }

  private static boolean isTerminal(String dayStatus) {
    return "SETTLED".equals(dayStatus)
        || "FAILED".equals(dayStatus)
        || "SKIPPED".equals(dayStatus)
        || "MANUAL_RELEASED".equals(dayStatus);
  }

  private static Instant resolve(LocalDate bizDate, LocalTime localTime, ZoneId zone) {
    LocalDateTime localOccurrenceTime = LocalDateTime.of(bizDate, localTime);
    ZonedDateTime zonedOccurrenceTime = localOccurrenceTime.atZone(zone);
    return zonedOccurrenceTime.toInstant();
  }

  private static void validate(DefinitionRequest request) {
    boolean requiredFieldsAreValid = EmptyChecks.isNotNull(request)
        && valid(request.tenantId(), 64)
        && valid(request.calendarCode(), 128)
        && EmptyChecks.isNotNull(request.workflowDefinitionId())
        && request.workflowDefinitionId() > 0
        && valid(request.fileGroupCode(), 128)
        && EmptyChecks.isNotNull(request.expectedLocalTime());
    boolean dueLocalTimeIsValidWhenRequiredFieldsAreValid = !requiredFieldsAreValid
        || EmptyChecks.isNull(request.dueLocalTime())
        || request.dueLocalTime().isAfter(request.expectedLocalTime());
    boolean definitionIsValid =
        requiredFieldsAreValid && dueLocalTimeIsValidWhenRequiredFieldsAreValid;
    if (!definitionIsValid) {
      throw new IllegalArgumentException(
          "tenant, calendar, workflowDefinitionId, fileGroupCode and expectedLocalTime are required; dueLocalTime must be later on the same business date");
    }
  }

  private static boolean valid(String value, int maxLength) {
    return Texts.hasText(value) && value.trim().length() <= maxLength;
  }

  public record DefinitionRequest(
      String tenantId,
      String calendarCode,
      Long workflowDefinitionId,
      String fileGroupCode,
      LocalTime expectedLocalTime,
      LocalTime dueLocalTime,
      boolean enabled) {}
}
