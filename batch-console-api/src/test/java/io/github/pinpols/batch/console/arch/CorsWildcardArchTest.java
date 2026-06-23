package io.github.pinpols.batch.console.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * P2-1(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md): 静态扫描 batch-console-api 主源码,
 * 禁止任何 CORS API 直接传入通配符 {@code "*"} —— {@code allowCredentials=true} 与 {@code *} 不兼容(W3C),
 * 但代码层若漏配只会变成"浏览器静默 reject preflight",上线后才会被发现。
 *
 * <p>覆盖模式:
 *
 * <ul>
 *   <li>{@code setAllowedOrigins(List.of("*", ...))} / {@code addAllowedOrigin("*")}
 *   <li>{@code allowedOrigins("*", ...)}({@code CorsRegistry} fluent)
 *   <li>{@code .addAllowedOriginPattern("*")} 同等暴露面也拒(允许显式 wildcard 模式有需要时单独 review 加白)
 * </ul>
 */
class CorsWildcardArchTest {

  private static final Path MAIN_SOURCES =
      Path.of("src/main/java/com/example/batch/console").toAbsolutePath();

  /** 匹配 setAllowedOrigins / addAllowedOrigin / allowedOrigins / addAllowedOriginPattern 含 "*" 调用 */
  private static final Pattern WILDCARD_CALL =
      Pattern.compile(
          "(?:setAllowedOrigins|addAllowedOrigin|addAllowedOriginPattern|allowedOrigins)\\s*\\("
              + "[^)]*\"\\*\"");

  @Test
  void noWildcardOriginInCorsApi() throws IOException {
    if (!Files.isDirectory(MAIN_SOURCES)) {
      // 跨构建路径异常:不存在则跳过,但让信号留在 stdout 方便排查
      System.err.println("[CorsWildcardArchTest] skip: source dir not found " + MAIN_SOURCES);
      return;
    }
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
      walk.filter(p -> p.toString().endsWith(".java"))
          .forEach(
              p -> {
                try {
                  String src = Files.readString(p, StandardCharsets.UTF_8);
                  if (WILDCARD_CALL.matcher(src).find()) {
                    offenders.add(p.toString());
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    assertThat(offenders)
        .as(
            "CORS API 不得调用 setAllowedOrigins/addAllowedOrigin/allowedOrigins/"
                + "addAllowedOriginPattern 传入 \"*\" —— 与 allowCredentials=true 不兼容(W3C)")
        .isEmpty();
  }
}
