package com.example.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema.ParamSpec;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema.SecurityGate;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lane H —— Atomic executor ↔ console schema 漂移守护契约测试。
 *
 * <p>{@link ConsoleAtomicTaskTypeSchemaService#CATALOG} 是 FE 表单的单一权威源,但 console-api 不依赖 {@code
 * batch-worker-atomic}(ADR-029 安全隔离),只能手工维护静态镜像。本测试通过反射拉取四个 {@code *ExecutorProperties} 字段 + 四个
 * {@code *TaskExecutor.PARAM_*} 常量,与 CATALOG 做集合对账:
 *
 * <ul>
 *   <li>CATALOG {@code parameters[].name} ↔ {@code *TaskExecutor} 中所有 {@code PARAM_*} 常量值
 *       <b>必须完全相等</b>
 *   <li>CATALOG {@code securityGates[].field} <b>必须都是</b> {@code *ExecutorProperties}
 *       的字段子集(Properties 允许有非 security 的运行时调参字段,如 {@code defaultTimeout}/{@code maxRetries},不强制反向)
 * </ul>
 *
 * <p>失败 assertion 给出完整差集,提示要改的两个文件。本测试 <b>不修复</b> 任何漂移 —— 漂移即 bug,需开发者人工同步。
 */
class ConsoleAtomicTaskTypeSchemaContractTest {

  private static final String SQL_EXECUTOR_FQCN =
      "com.example.batch.worker.atomic.sql.SqlTaskExecutor";
  private static final String SQL_PROPS_FQCN =
      "com.example.batch.worker.atomic.sql.SqlExecutorProperties";
  private static final String STORED_PROC_EXECUTOR_FQCN =
      "com.example.batch.worker.atomic.storedproc.StoredProcTaskExecutor";
  private static final String STORED_PROC_PROPS_FQCN =
      "com.example.batch.worker.atomic.storedproc.StoredProcExecutorProperties";
  private static final String SHELL_EXECUTOR_FQCN =
      "com.example.batch.worker.atomic.shell.ShellTaskExecutor";
  private static final String SHELL_PROPS_FQCN =
      "com.example.batch.worker.atomic.shell.ShellExecutorProperties";
  private static final String HTTP_EXECUTOR_FQCN =
      "com.example.batch.worker.atomic.http.HttpTaskExecutor";
  private static final String HTTP_PROPS_FQCN =
      "com.example.batch.worker.atomic.http.HttpExecutorProperties";

  private static final Map<String, ExecutorContract> CONTRACTS =
      Map.of(
          "sql", new ExecutorContract(SQL_EXECUTOR_FQCN, SQL_PROPS_FQCN),
          "stored_proc", new ExecutorContract(STORED_PROC_EXECUTOR_FQCN, STORED_PROC_PROPS_FQCN),
          "shell", new ExecutorContract(SHELL_EXECUTOR_FQCN, SHELL_PROPS_FQCN),
          "http", new ExecutorContract(HTTP_EXECUTOR_FQCN, HTTP_PROPS_FQCN));

  private final ConsoleAtomicTaskTypeSchemaService service =
      new ConsoleAtomicTaskTypeSchemaService();

  static Stream<Arguments> taskTypes() {
    return Stream.of(
        Arguments.of("sql"),
        Arguments.of("stored_proc"),
        Arguments.of("shell"),
        Arguments.of("http"));
  }

  @ParameterizedTest(name = "[{index}] {0} executor PARAM_* 常量与 CATALOG parameters 必须完全一致")
  @MethodSource("taskTypes")
  void parameterNamesMatchExecutorParamConstants(String taskType) throws Exception {
    // 准备
    AtomicTaskTypeSchema schema = locateSchema(taskType);
    Set<String> catalogParams =
        schema.parameters().stream()
            .map(ParamSpec::name)
            .collect(Collectors.toCollection(TreeSet::new));
    Class<?> executorClass = Class.forName(CONTRACTS.get(taskType).executorFqcn());
    Set<String> executorParams = collectParamConstantValues(executorClass);

    // 执行
    Set<String> missingInCatalog = difference(executorParams, catalogParams);
    Set<String> staleInCatalog = difference(catalogParams, executorParams);

    // 断言 —— 失败信息要让人一眼看到要改哪两个文件
    assertThat(missingInCatalog)
        .as(
            "[漂移] %s executor 声明了 PARAM_* 常量 %s,但"
                + " ConsoleAtomicTaskTypeSchemaService.CATALOG.parameters 缺失。请在 console schema"
                + " 中补齐(FE 表单单一权威源)。",
            taskType, missingInCatalog)
        .isEmpty();
    assertThat(staleInCatalog)
        .as(
            "[漂移] ConsoleAtomicTaskTypeSchemaService.CATALOG.parameters 提到 %s 字段 %s,但 %s 已无对应"
                + " PARAM_* 常量。请删除 console schema 中的陈旧字段。",
            taskType, staleInCatalog, executorClass.getSimpleName())
        .isEmpty();
  }

  @ParameterizedTest(name = "[{index}] {0} executor SecurityGate.field 必须存在于 ExecutorProperties")
  @MethodSource("taskTypes")
  void securityGateFieldsExistOnExecutorProperties(String taskType) throws Exception {
    // 准备
    AtomicTaskTypeSchema schema = locateSchema(taskType);
    Set<String> catalogGates =
        schema.securityGates().stream()
            .map(SecurityGate::field)
            .collect(Collectors.toCollection(TreeSet::new));
    Class<?> propsClass = Class.forName(CONTRACTS.get(taskType).propertiesFqcn());
    Set<String> propsFieldNames = collectInstanceFieldNames(propsClass);

    // 执行 —— 仅检 catalog 是否引到不存在的 props 字段;Properties 字段是上集(允许有非 security 的字段)
    Set<String> staleInCatalog = difference(catalogGates, propsFieldNames);

    // 断言
    assertThat(staleInCatalog)
        .as(
            "[漂移] ConsoleAtomicTaskTypeSchemaService.CATALOG.securityGates 提到 %s 字段 %s,但 %s 不存在该字段。"
                + "executor 已重命名/删除,请同步更新 console schema(或反向:Properties 加字段)。",
            taskType, staleInCatalog, propsClass.getSimpleName())
        .isEmpty();
  }

  private AtomicTaskTypeSchema locateSchema(String taskType) {
    return service.schema().stream()
        .filter(s -> s.taskType().equals(taskType))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("CATALOG 缺失 taskType=" + taskType + ",这是 4 类内置原子任务之一,不应被删"));
  }

  /**
   * 抓 executor 类中所有 {@code static final String PARAM_*} 常量的"值",作为该 executor 实际接受的参数名集合。
   *
   * <p>注意:常量声明可见性 ≤ package-private(见 ShellTaskExecutor 等),需 {@code setAccessible(true)}。
   */
  private static Set<String> collectParamConstantValues(Class<?> executorClass)
      throws IllegalAccessException {
    Set<String> values = new TreeSet<>();
    for (Field f : executorClass.getDeclaredFields()) {
      int mods = f.getModifiers();
      if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
        continue;
      }
      if (!f.getName().startsWith("PARAM_")) {
        continue;
      }
      if (f.getType() != String.class) {
        continue;
      }
      f.setAccessible(true);
      values.add((String) f.get(null));
    }
    return values;
  }

  /** 抓 Properties 类所有实例字段名(过滤 static / synthetic,Lombok @Data 生成的 getter/setter 不动字段)。 */
  private static Set<String> collectInstanceFieldNames(Class<?> propsClass) {
    return Arrays.stream(propsClass.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()))
        .filter(f -> !f.isSynthetic())
        .map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> difference(Set<String> a, Set<String> b) {
    Set<String> diff = new TreeSet<>(a);
    diff.removeAll(b);
    return diff;
  }

  /** 一个 atomic executor 的两端类名对(executor 持 PARAM_*,Properties 持 security gate 字段)。 */
  private record ExecutorContract(String executorFqcn, String propertiesFqcn) {}
}
