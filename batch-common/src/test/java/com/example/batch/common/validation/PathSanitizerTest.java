package com.example.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathSanitizerTest {

  @TempDir Path tempDir;

  @Test
  void sanitize_normalPath_returnsAbsoluteNormalized() {
    Path result = PathSanitizer.sanitize("/tmp/batch/file.csv");
    assertThat(result.isAbsolute()).isTrue();
    assertThat(result.toString()).doesNotContain("..");
  }

  @Test
  void sanitize_nullPath_throwsIllegalArgument() {
    assertThatThrownBy(() -> PathSanitizer.sanitize(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sanitize_blankPath_throwsIllegalArgument() {
    assertThatThrownBy(() -> PathSanitizer.sanitize("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sanitize_pathWithDotDot_throwsSecurity() {
    assertThatThrownBy(() -> PathSanitizer.sanitize("/tmp/../etc/passwd"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("..");
  }

  @Test
  void sanitize_relativePathWithDotDot_throwsSecurity() {
    assertThatThrownBy(() -> PathSanitizer.sanitize("foo/../../secret"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void sanitize_withBaseDir_validSubpath_returnsPath() {
    Path result = PathSanitizer.sanitize(tempDir.resolve("sub/file.txt").toString(), tempDir);
    assertThat(result.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
  }

  @Test
  void sanitize_withBaseDir_escapingPath_throwsSecurity() {
    Path other = Path.of("/etc");
    assertThatThrownBy(
            () ->
                PathSanitizer.sanitize(
                    "/etc/passwd", other.getParent() == null ? Path.of("/tmp") : tempDir))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("escapes");
  }

  @Test
  void sanitize_withBaseDir_dotDot_throwsSecurity() {
    assertThatThrownBy(() -> PathSanitizer.sanitize(tempDir + "/../other", tempDir))
        .isInstanceOf(SecurityException.class);
  }
}
