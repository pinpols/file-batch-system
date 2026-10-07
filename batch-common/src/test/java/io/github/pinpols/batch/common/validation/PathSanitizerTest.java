package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PathSanitizer: 路径规范化、基目录约束与目录穿越防护")
class PathSanitizerTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("普通路径被规范化为不含上级目录的绝对路径")
  void sanitize_normalPath_returnsAbsoluteNormalized() {
    Path result = PathSanitizer.sanitize("/tmp/batch/file.csv");
    assertThat(result).isAbsolute();
    assertThat(result.toString()).doesNotContain("..");
  }

  @Test
  @DisplayName("入参为空时抛出参数非法异常")
  void sanitize_nullPath_throwsIllegalArgument() {
    assertThatThrownBy(() -> PathSanitizer.sanitize(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("入参为空白时抛出参数非法异常")
  void sanitize_blankPath_throwsIllegalArgument() {
    assertThatThrownBy(() -> PathSanitizer.sanitize("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("绝对路径含上级目录跳转时按安全异常拒绝")
  void sanitize_pathWithDotDot_throwsSecurity() {
    assertThatThrownBy(() -> PathSanitizer.sanitize("/tmp/../etc/passwd"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("..");
  }

  @Test
  @DisplayName("相对路径越出上级目录时按安全异常拒绝")
  void sanitize_relativePathWithDotDot_throwsSecurity() {
    assertThatThrownBy(() -> PathSanitizer.sanitize("foo/../../secret"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  @DisplayName("限定基目录下的合法子路径正常返回")
  void sanitize_withBaseDir_validSubpath_returnsPath() {
    Path result = PathSanitizer.sanitize(tempDir.resolve("sub/file.txt").toString(), tempDir);
    assertThat(result.toString())
        .startsWith(tempDir.toAbsolutePath().normalize().toString());
  }

  @Test
  @DisplayName("逃出基目录的路径按安全异常拒绝")
  void sanitize_withBaseDir_escapingPath_throwsSecurity() {
    Path other = Path.of("/etc");
    assertThatThrownBy(() -> PathSanitizer.sanitize(
            "/etc/passwd", other.getParent() == null ? Path.of("/tmp") : tempDir))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("escapes");
  }

  @Test
  @DisplayName("基目录场景下的上级目录跳转按安全异常拒绝")
  void sanitize_withBaseDir_dotDot_throwsSecurity() {
    assertThatThrownBy(() -> PathSanitizer.sanitize(tempDir + "/../other", tempDir))
        .isInstanceOf(SecurityException.class);
  }
}
