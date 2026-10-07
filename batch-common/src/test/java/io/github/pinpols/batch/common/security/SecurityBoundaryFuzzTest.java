package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.page.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;

/** 对用户可控游标和日志字段执行覆盖引导 fuzz，守住解析容错与日志单行边界。 */
@Tag("fuzz")
class SecurityBoundaryFuzzTest {

  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  @FuzzTest(maxDuration = "30s")
  void hostileBytesDoNotEscapeCursorAndLoggingBoundaries(byte[] input) {
    String text = new String(input, StandardCharsets.UTF_8);

    // 损坏游标必须安全降级，不得把解析异常传播到查询入口。
    assertThat(CursorCodec.decode(text)).isNotNull();

    // 用户字段进入日志后必须保持单行，覆盖 CR/LF 与 Unicode 行分隔符。
    assertThat(LINE_BREAK.matcher(LogSanitizer.value(text)).find()).isFalse();
  }
}
