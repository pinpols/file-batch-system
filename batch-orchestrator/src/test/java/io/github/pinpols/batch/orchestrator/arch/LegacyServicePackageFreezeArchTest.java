package io.github.pinpols.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 旧 {@code orchestrator.service} 包冻结(CONV-20261103-01):新服务进入 {@code application.service.<ctx>},存量随功能改动迁出,
 * 迁出后同步从清单删除。清单只能缩小,不得为新类追加。
 */
@DisplayName("架构守护:旧 orchestrator.service 包只保留冻结清单内的存量类")
class LegacyServicePackageFreezeArchTest {

  private static final String LEGACY_PACKAGE = "io.github.pinpols.batch.orchestrator.service";

  private static final Set<String> FROZEN_CLASSES = Set.of(
      "BatchDayAlertDetails",
      "BatchDayGateService",
      "BatchDayOperateCommand",
      "BatchDayOperationService",
      "BatchDayTimePolicyResolver",
      "CutoffScheduleResolver",
      "DefaultLaunchService",
      "DefaultLaunchValidationService",
      "DefaultWorkerRegistryService",
      "DispatchChannelSelection",
      "DispatchChannelSnapshotService",
      "DispatchSecretSnapshotResolver",
      "FileTemplateSnapshotService",
      "JobDependencyWaitingLaunchService",
      "LaunchBatchDayService",
      "LaunchParamResolver",
      "LaunchRequestIdentityGuard",
      "LaunchService",
      "LaunchValidationService",
      "WorkerRegistryServerService",
      "failure.FailureClassifier");

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(LEGACY_PACKAGE);

  @Test
  @DisplayName("旧 service 包内的顶层类均在冻结清单内,已迁出的类同步移出清单")
  void shouldOnlyContainFrozenClasses_whenInLegacyServicePackage() {
    Set<String> actual = new TreeSet<>();
    for (JavaClass javaClass : CLASSES) {
      if (javaClass.isTopLevelClass()) {
        actual.add(javaClass.getName().substring(LEGACY_PACKAGE.length() + 1));
      }
    }

    assertThat(actual)
        .as("新服务放入 application.service.<ctx>;迁出旧类时同步删除 FROZEN_CLASSES 条目")
        .containsExactlyInAnyOrderElementsOf(FROZEN_CLASSES);
  }
}
