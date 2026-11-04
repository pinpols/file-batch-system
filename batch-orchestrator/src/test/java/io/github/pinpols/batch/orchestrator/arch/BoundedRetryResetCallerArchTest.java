package io.github.pinpols.batch.orchestrator.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.orchestrator.application.service.governance.DefaultFileGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryRequeueCoordinator;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ASSESS-20261102-DEC-01：Task/Partition 回到 READY 只允许经三个受限入口——重试重排队、文件重派和租约回收。
 *
 * <p>这些入口在同一事务内先复位 Partition 再以版本 CAS 复位 Task，任一步失配即整体回滚，并在此之前校验父级与未知结果。新增复位入口须先在
 * core-model.md 的受限例外中登记，再扩展本清单。
 */
@DisplayName("架构守护:Task/Partition 复位只能由登记的受限入口调用")
class BoundedRetryResetCallerArchTest {

  private static final Map<Class<?>, Set<String>> RESET_METHODS = Map.of(
      JobTaskMapper.class, Set.of("resetForRetry", "resetForLeaseReclaim"),
      JobPartitionMapper.class, Set.of("resetForDispatch", "resetForLeaseReclaim"));

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.orchestrator..");

  @Test
  @DisplayName("重试重排队、文件重派与租约回收之外的生产类不调用复位语句")
  void shouldOnlyResetFromRegisteredEntryPoints() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(
            RetryRequeueCoordinator.class,
            DefaultFileGovernanceService.class,
            PartitionReclaimUnit.class)
        .should()
        .callMethodWhere(resetStatementCall())
        .check(CLASSES);
  }

  private static DescribedPredicate<JavaMethodCall> resetStatementCall() {
    return DescribedPredicate.describe(
        "call a Task/Partition reset statement",
        call -> RESET_METHODS.entrySet().stream()
            .anyMatch(entry -> call.getTargetOwner().isEquivalentTo(entry.getKey())
                && entry.getValue().contains(call.getName())));
  }
}
