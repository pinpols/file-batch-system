package io.github.pinpols.batch.common.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 守护 MyBatis SQL 使用 Mapper XML，避免注解中的 SQL 绕开 XML 治理。 */
@DisplayName("MyBatis SQL 必须放在 Mapper XML")
class MyBatisSqlAnnotationConventionTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Set<String> EXCLUDED =
      Set.of(".git", "target", "node_modules", "logs", "build");
  private static final String SQL_ANNOTATION =
      "(?:(?:Select|Insert|Update|Delete)(?:Provider)?|SelectKey)";
  private static final Pattern LITERALS_AND_COMMENTS = Pattern.compile(
      "(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
  private static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\.(" + SQL_ANNOTATION + "|\\*)\\s*;");
  private static final Pattern ANNOTATION = Pattern.compile(
      "@\\s*((?:org\\.apache\\.ibatis\\.annotations\\.)?" + SQL_ANNOTATION + ")\\b");

  @Test
  @DisplayName("生产与测试 Java 源码不使用 MyBatis SQL 注解")
  void shouldKeepMyBatisSqlInXml() throws IOException {
    List<String> violations = new ArrayList<>();
    int[] scanned = {0};
    Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
        return directory.getFileName() != null
                && EXCLUDED.contains(directory.getFileName().toString())
            ? FileVisitResult.SKIP_SUBTREE
            : FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
          throws IOException {
        String path = ROOT.relativize(file).toString().replace('\\', '/');
        if (path.endsWith(".java")
            && (path.contains("/src/main/java/") || path.contains("/src/test/java/"))) {
          scanned[0]++;
          for (String annotation :
              findSqlAnnotations(Files.readString(file, StandardCharsets.UTF_8))) {
            violations.add(path + ": " + annotation);
          }
        }
        return FileVisitResult.CONTINUE;
      }
    });
    assertThat(scanned[0]).as("Java 源码扫描范围必须有效").isGreaterThan(100);
    assertThat(violations).as("MyBatis SQL 应移至 Mapper XML").isEmpty();
  }

  @Test
  @DisplayName("识别导入和全限定的注解，忽略注释、文本及无关注解")
  void shouldRecognizeMyBatisAnnotationsWithoutTreatingTextAsCode() {
    assertThat(findSqlAnnotations(
            "import org.apache.ibatis.annotations.Select;\n@Select(\"select 1\")"))
        .containsExactly("Select");
    assertThat(findSqlAnnotations(
            "import org.apache.ibatis.annotations.*;\n@UpdateProvider(type=X.class)"))
        .containsExactly("UpdateProvider");
    assertThat(findSqlAnnotations("@org.apache.ibatis.annotations.Delete(\"delete\")"))
        .containsExactly("org.apache.ibatis.annotations.Delete");
    assertThat(findSqlAnnotations(
            "import org.apache.ibatis.annotations.SelectKey;\n@SelectKey(statement=\"select 1\")"))
        .containsExactly("SelectKey");
    assertThat(
            findSqlAnnotations("@org.apache.ibatis.annotations.SelectKey(statement=\"select 1\")"))
        .containsExactly("org.apache.ibatis.annotations.SelectKey");
    assertThat(findSqlAnnotations("import org.apache.ibatis.annotations.*;\n@SelectKey"))
        .containsExactly("SelectKey");
    assertThat(findSqlAnnotations(
            "import org.apache.ibatis.annotations.*;\nimport com.example.SelectKey;\n@SelectKey"))
        .isEmpty();
    assertThat(findSqlAnnotations(
            "// @org.apache.ibatis.annotations.SelectKey(statement=\"select 1\")"))
        .isEmpty();
    assertThat(findSqlAnnotations(
            "import org.apache.ibatis.annotations.*;\n" + "import com.example.Select;\n@Select"))
        .isEmpty();
    assertThat(
            findSqlAnnotations(
                "// @org.apache.ibatis.annotations.Select(\"x\")\n"
                    + "String x = \"@org.apache.ibatis.annotations.Insert\";\n@Select(\"other annotation\")"))
        .isEmpty();
  }

  private static List<String> findSqlAnnotations(String source) {
    String code = LITERALS_AND_COMMENTS.matcher(source).replaceAll(" ");
    Matcher imports = IMPORT.matcher(code);
    Set<String> imported = new HashSet<>();
    Set<String> otherExplicitImports = new HashSet<>();
    while (imports.find()) {
      if (imports.group(1).equals("org.apache.ibatis.annotations")) {
        imported.add(imports.group(2));
      } else if (!imports.group(2).equals("*")) {
        otherExplicitImports.add(imports.group(2));
      }
    }
    List<String> found = new ArrayList<>();
    Matcher annotations = ANNOTATION.matcher(code);
    while (annotations.find()) {
      String name = annotations.group(1);
      if (name.startsWith("org.apache.ibatis.annotations.")
          || imported.contains(name)
          || (imported.contains("*") && !otherExplicitImports.contains(name))) {
        found.add(name);
      }
    }
    return found;
  }
}
