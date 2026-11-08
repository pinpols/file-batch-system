package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.util.AntPathMatcher;

/**
 * E2E 控制台应用 MapperScan 覆盖守护。
 *
 * <p>2026-11-08 main-red：P1-A Stage 1 把配置类 Mapper 从 {@code console.mapper} 迁到 {@code
 * console.domain.config.mapper} 后，端到端控制台应用仍按旧包白名单声明 {@code @MapperScan}，导致 {@code
 * ConfigCacheInvalidationOutboxMapper} 找不到、7 个 E2E shard 的 ApplicationContext 启动失败。组件扫描已有 {@link
 * io.github.pinpols.batch.e2e.arch.SpringWiringGuardArchTest} 守护，但 MapperScan 没有对应守卫，本类补齐。
 *
 * <p>守护策略：console-api 下任何 {@code console.*.mapper} 包中的接口，都必须被端到端控制台应用声明的 {@code
 * @MapperScan} basePackages 覆盖；并保证 mapper 包已归入 {@code console.domain.<ctx>.mapper}，不再回退到旧顶层包。
 */
@DisplayName("E2E 控制台应用 MapperScan 覆盖守护:校验 console mapper 包被端到端应用扫描,防 P1-A 迁移后上下文启动失败")
class E2eConsoleMapperScanGuardArchTest {

  private static final String CONSOLE_DOMAIN_PREFIX = "io.github.pinpols.batch.console.domain.";
  private static final String CONSOLE_MAPPER_PACKAGE_PREFIX = "io.github.pinpols.batch.console.";

  /** 显式声明 console mapper 扫描的 E2E 应用/测试配置。 */
  private static final List<Class<?>> CONSOLE_MAPPER_SCAN_APPS = List.of(
      E2eConsoleImportApplication.class, RuntimeConfigImportDispatchE2eIT.ConsoleConfig.class);

  private static final JavaClasses ALL_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
      .importPackages("io.github.pinpols.batch");

  private static final AntPathMatcher PACKAGE_MATCHER = new AntPathMatcher();

  @Test
  @DisplayName("所有 console mapper 包必须位于 domain.<ctx>.mapper,且被端到端控制台应用的 MapperScan 覆盖")
  void shouldCoverAllConsoleMapperPackages_whenE2eAppsDeclareMapperScan() {
    Set<String> consoleMapperPackages = ALL_CLASSES.stream()
        .filter(javaClass -> javaClass.isInterface())
        .map(javaClass -> javaClass.getPackageName())
        .filter(packageName -> packageName.startsWith(CONSOLE_MAPPER_PACKAGE_PREFIX))
        .filter(packageName -> packageName.endsWith(".mapper"))
        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

    assertThat(consoleMapperPackages).as("未找到任何 console mapper 包,守护本身可能失效").isNotEmpty();

    Set<String> legacyMapperPackages = new TreeSet<>(consoleMapperPackages);
    legacyMapperPackages.removeIf(packageName -> packageName.startsWith(CONSOLE_DOMAIN_PREFIX));
    assertThat(legacyMapperPackages)
        .as("console mapper 仍位于旧顶层包,需归入 console.domain.<ctx>.mapper:" + legacyMapperPackages)
        .isEmpty();

    for (Class<?> app : CONSOLE_MAPPER_SCAN_APPS) {
      Set<String> uncovered = new TreeSet<>(consoleMapperPackages);
      uncovered.removeIf(packageName -> coveredByAnyMapperScan(app, packageName));
      assertThat(uncovered)
          .as(
              "E2E 应用 %s 的 @MapperScan 未覆盖以下 console mapper 包(会 NoSuchBean 启动失败,见"
                  + " 2026-11-08 main-red):%s → 在 @MapperScan basePackages 里补齐 "
                  + "console.domain.**.mapper",
              app.getName(), uncovered)
          .isEmpty();
    }
  }

  private static boolean coveredByAnyMapperScan(Class<?> app, String packageName) {
    List<String> basePackages = new ArrayList<>();
    for (MapperScan mapperScan : app.getAnnotationsByType(MapperScan.class)) {
      basePackages.addAll(List.of(mapperScan.basePackages()));
    }
    String candidate = packageName.replace('.', '/');
    return basePackages.stream().anyMatch(basePackage -> {
      String pattern = basePackage.replace('.', '/');
      return PACKAGE_MATCHER.match(pattern, candidate)
          || PACKAGE_MATCHER.match(pattern + "/**", candidate);
    });
  }
}
