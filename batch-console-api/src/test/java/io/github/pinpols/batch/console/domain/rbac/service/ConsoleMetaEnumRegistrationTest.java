package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.DictEnum;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 守护测试：batch-common/.../enums 下每个公共枚举必须
 *
 * <ol>
 *   <li>实现 {@link DictEnum} 契约；
 *   <li>要么在 {@link ConsoleMetaQueryService} 的 REGISTRATIONS 中注册（对外暴露）， 要么加入本文件 {@code EXCLUDED}
 *       白名单并注明原因（明确不对外）。
 * </ol>
 *
 * <p>新增枚举忘记登记时，CI 阶段就会失败。
 */
class ConsoleMetaEnumRegistrationTest {

  private static final String ENUM_PACKAGE = "io.github.pinpols.batch.common.enums";

  /** 不对外暴露的枚举；新增条目必须附上原因。 */
  private static final Set<String> EXCLUDED =
      Set.of(
          "ResultCode", // RPC 错误码协议，非业务字典
          "WorkflowNodeCode", // 内部节点标记常量（仅 START/END）
          "JobStatus", // 死代码（无其它引用），候选移除
          "BatchLifecycleStatus" // 派生公共投影，非可选字典；用户仍选具体 *Status，UI 不暴露
          );

  @Test
  void everyCommonEnumImplementsDictEnum() {
    Set<String> scanned = scanEnumSimpleNames();
    assertThat(scanned).as("扫描器应能发现至少 50 个公共枚举，否则说明扫描失败").hasSizeGreaterThan(50);

    Set<String> nonCoded = new TreeSet<>();
    for (String simpleName : scanned) {
      Class<?> clazz = loadEnumClass(simpleName);
      if (!DictEnum.class.isAssignableFrom(clazz)) {
        nonCoded.add(simpleName);
      }
    }
    assertThat(nonCoded).as("batch-common 下所有公共枚举必须实现 DictEnum（code/label）").isEmpty();
  }

  @Test
  void everyCommonEnumIsRegisteredOrExplicitlyExcluded() {
    Set<String> scanned = scanEnumSimpleNames();
    Set<String> registered =
        ConsoleMetaQueryService.registeredEnumClasses().stream()
            .map(Class::getSimpleName)
            .collect(Collectors.toUnmodifiableSet());

    Set<String> unaccounted = new TreeSet<>(scanned);
    unaccounted.removeAll(registered);
    unaccounted.removeAll(EXCLUDED);

    assertThat(unaccounted)
        .as(
            "新增枚举必须二选一：在 ConsoleMetaQueryService.REGISTRATIONS 中注册，"
                + "或加入 ConsoleMetaEnumRegistrationTest.EXCLUDED 白名单并注明原因")
        .isEmpty();
  }

  @Test
  void excludedEnumsAreNotRegistered() {
    Set<String> registered =
        ConsoleMetaQueryService.registeredEnumClasses().stream()
            .map(Class::getSimpleName)
            .collect(Collectors.toUnmodifiableSet());

    assertThat(registered)
        .as("EXCLUDED 白名单中的枚举不应同时出现在 REGISTRATIONS 中")
        .doesNotContainAnyElementsOf(EXCLUDED);
  }

  @Test
  void excludedEntriesReferExistingEnums() {
    Set<String> scanned = scanEnumSimpleNames();
    assertThat(scanned).as("EXCLUDED 白名单条目必须对应真实存在的枚举（防止原因注释滞留、枚举已删除）").containsAll(EXCLUDED);
  }

  private static Set<String> scanEnumSimpleNames() {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    String pattern = "classpath*:" + ENUM_PACKAGE.replace('.', '/') + "/*.class";
    Resource[] resources;
    try {
      resources = resolver.getResources(pattern);
    } catch (IOException e) {
      throw new IllegalStateException("扫描枚举包失败: " + ENUM_PACKAGE, e);
    }
    Set<String> names = new TreeSet<>();
    for (Resource resource : resources) {
      String filename = resource.getFilename();
      if (filename == null || !filename.endsWith(".class") || filename.contains("$")) {
        continue;
      }
      String simpleName = filename.substring(0, filename.length() - ".class".length());
      Class<?> clazz = loadEnumClass(simpleName);
      if (clazz != null && clazz.isEnum()) {
        names.add(simpleName);
      }
    }
    return names;
  }

  private static Class<?> loadEnumClass(String simpleName) {
    try {
      return Class.forName(ENUM_PACKAGE + "." + simpleName);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }
}
