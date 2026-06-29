package io.github.pinpols.batch.console.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 守护测试：5 个"受缓存配置" application service（job-definition / workflow-definition / business-calendar /
 * batch-window / tenant-quota-policy）里，任何 <b>写</b>了 mapper（调用了
 * insert/update/delete/upsert/batch/toggle/copy 类方法）的方法，<b>必须同时</b>调用 {@link
 * io.github.pinpols.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService} 的某个
 * evict* 方法，否则 orchestrator 在 launch 热路径上读到的 Redis 配置缓存会留陈旧值。
 *
 * <p>背景：之前用 {@code @InvalidatesConsoleCache} 注解 + {@code ConsoleCacheInvalidationAspect} AOP
 * 切面声明式兜底， 现已删除（无调用方），缓存失效完全走 service 内 <b>手动</b>调用 {@code
 * cacheInvalidationService.evict*}。手动调用最大的风险是 "有人加了新写路径方法却忘了清缓存" —— 本测试就是这道护栏：新写方法一旦写 mapper 而漏
 * evict，surefire 阶段即红，无需等评审。
 *
 * <p>判定口径（保守，宁可多报）：
 *
 * <ul>
 *   <li><b>"写"</b> = 方法体内（{@link JavaMethod#getMethodCallsFromSelf()}）调到了某个 owner 类名以 {@code
 *       Mapper} 结尾、且方法名前缀命中 {@link #MAPPER_WRITE_PREFIXES} 的方法。读方法（selectBy* / countBy* /
 *       selectById*）不命中，不受约束。
 *   <li><b>满足</b> = 同一方法体内也存在对 {@code ConsoleConfigCacheInvalidationService} 任意方法的调用。
 * </ul>
 *
 * <p>只对这 5 个类施加（{@link #CACHED_SERVICE_FQNS}）。其余 console service 写的是非缓存表，不在护栏内。
 */
class ConsoleCacheInvalidationCoverageTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.pinpols.batch.console..");

  /** 受缓存约束的 5 个 application service 实现类（全限定名）。 */
  private static final Set<String> CACHED_SERVICE_FQNS =
      Set.of(
          "io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleJobDefinitionApplicationService",
          "io.github.pinpols.batch.console.infrastructure.workflow.DefaultConsoleWorkflowDefinitionApplicationService",
          "io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleCalendarApplicationService",
          "io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleBatchWindowApplicationService",
          "io.github.pinpols.batch.console.infrastructure.config.DefaultConsoleQuotaPolicyApplicationService");

  /**
   * mapper 写方法名前缀集合（按这 5 个 service 实际调用的 mapper 方法名归纳）：insert / batchInsert / update* /
   * updateAndBumpVersion / delete* / upsert* / toggleEnabled / batchToggleEnabled /
   * copyJobDefinition / insertVersionSnapshot。读方法 selectBy* / countBy* / selectById* 不在内。
   */
  private static final List<String> MAPPER_WRITE_PREFIXES =
      List.of("insert", "update", "delete", "upsert", "batch", "toggle", "copy");

  private static final String CACHE_SERVICE_FQN =
      "io.github.pinpols.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService";

  /**
   * 显式豁免集："全限定类名#方法名"。每条必须注释说明"为何这个写方法语义上不需要 evict"（典型：写的是非缓存的侧表 / 历史快照表，不进 orchestrator 读热点；或私有
   * helper，其全部 public 调用方已对父键 evict）。仅这 2 条 workflow 私有 helper 豁免；新增条目须有明确架构理由，否则护栏失效。
   */
  private static final Set<String> EXEMPTIONS =
      Set.of(
          // 私有 helper，被 create/update/fullUpdate 调用 —— 它写的是 workflow_node / workflow_edge
          // （workflow 定义聚合的一部分），三个 public 入口都在同事务里调 evictWorkflowDefinition 清父键，
          // 子表随父键一起失效，无需在 helper 内重复 evict。
          "io.github.pinpols.batch.console.infrastructure.workflow.DefaultConsoleWorkflowDefinitionApplicationService#upsertNodesAndEdges",
          // 私有 helper，仅 fullUpdate 调用 —— 它写的是 workflow_definition_version 历史快照表，
          // 不在 orchestrator launch 读热点缓存路径上（只供 console 版本 diff 列表读），故无需 evict 配置缓存；
          // fullUpdate 本身已对主定义 evictWorkflowDefinition。
          "io.github.pinpols.batch.console.infrastructure.workflow.DefaultConsoleWorkflowDefinitionApplicationService#appendVersionSnapshot");

  @Test
  void everyWriteMethodInCachedServicesMustEvictCache() {
    // 硬化:5 个受守护 service 必须都能解析到。若有人改名/移包,FQN 失配会让护栏"无方法可查"而静默通过
    // (空守护)——这里显式断言它们都在,改名即红,逼着同步更新 CACHED_SERVICE_FQNS。
    for (String fqn : CACHED_SERVICE_FQNS) {
      boolean present = CLASSES.stream().anyMatch(c -> c.getFullName().equals(fqn));
      Assertions.assertThat(present).as("受缓存 service 未找到(改名/移包了?护栏会静默失效,请同步更新): %s", fqn).isTrue();
    }
    ArchRule rule =
        ArchRuleDefinition.methods()
            .that(areDeclaredInCachedServices())
            .and()
            .areNotStatic()
            .should(evictCacheWheneverTheyWriteAMapper());
    rule.check(CLASSES);
  }

  private static DescribedPredicate<JavaMethod> areDeclaredInCachedServices() {
    return new DescribedPredicate<>("declared in a cached console config service") {
      @Override
      public boolean test(JavaMethod method) {
        return CACHED_SERVICE_FQNS.contains(method.getOwner().getFullName());
      }
    };
  }

  private static ArchCondition<JavaMethod> evictCacheWheneverTheyWriteAMapper() {
    return new ArchCondition<>(
        "evict console config cache whenever they call a mapper write method") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String memberKey = method.getOwner().getFullName() + "#" + method.getName();
        if (EXEMPTIONS.contains(memberKey)) {
          return;
        }
        boolean writesMapper = false;
        boolean evictsCache = false;
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
          JavaClass targetOwner = call.getTargetOwner();
          if (isMapperWrite(targetOwner, call.getName())) {
            writesMapper = true;
          }
          if (CACHE_SERVICE_FQN.equals(targetOwner.getFullName())) {
            evictsCache = true;
          }
        }
        if (writesMapper && !evictsCache) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " 调用了 mapper 写方法但没有调用 ConsoleConfigCacheInvalidationService 的 evict* —— "
                      + "可能留陈旧缓存。若该写表非 orchestrator 读热点，请加入 EXEMPTIONS 并注释理由；否则补 evict。"));
        }
      }
    };
  }

  private static boolean isMapperWrite(JavaClass owner, String methodName) {
    if (!owner.getSimpleName().endsWith("Mapper")) {
      return false;
    }
    for (String prefix : MAPPER_WRITE_PREFIXES) {
      if (methodName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
