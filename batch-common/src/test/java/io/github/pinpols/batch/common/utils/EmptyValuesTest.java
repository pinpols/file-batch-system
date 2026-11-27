package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("空或空白值判定: 空串、空白文本、集合与数组的 null-safe 语义")
class EmptyValuesTest {

    @Test
    @DisplayName("文本判定: 空串、纯空白与非空白互相区分,hasText 与 isBlank 互为正反")
    void shouldDistinguishEmptyBlankAndText() {
        assertThat(EmptyValues.isEmpty((String) null)).isTrue();
        assertThat(EmptyValues.isEmpty("")).isTrue();
        assertThat(EmptyValues.isEmpty(" ")).isFalse();
        assertThat(EmptyValues.isNotEmpty("value")).isTrue();

        assertThat(EmptyValues.isBlank(" ")).isTrue();
        assertThat(EmptyValues.isBlank("value")).isFalse();
        assertThat(EmptyValues.isNotBlank("value")).isTrue();
        assertThat(EmptyValues.hasText(" ")).isFalse();
        assertThat(EmptyValues.hasText("value")).isTrue();
        assertThat(EmptyValues.hasText((String) null)).isFalse();
    }

    @Test
    @DisplayName("集合与数组判定: 空值及零元素视为空, 含元素视为非空")
    void shouldHandleNullAndEmpty_whenCheckingCollectionsAndArrays() {
        assertThat(EmptyValues.isEmpty((List<?>) null)).isTrue();
        assertThat(EmptyValues.isEmpty(List.of())).isTrue();
        assertThat(EmptyValues.isEmpty(List.of("value"))).isFalse();
        assertThat(EmptyValues.isNotEmpty(List.of("value"))).isTrue();
        assertThat(EmptyValues.isEmpty((Map<?, ?>) null)).isTrue();
        assertThat(EmptyValues.isEmpty(Map.of())).isTrue();
        assertThat(EmptyValues.isEmpty(Map.of("key", "value"))).isFalse();
        assertThat(EmptyValues.isNotEmpty(Map.of("key", "value"))).isTrue();
        assertThat(EmptyValues.isEmpty((Object[]) null)).isTrue();
        assertThat(EmptyValues.isEmpty(new Object[0])).isTrue();
        assertThat(EmptyValues.isNotEmpty(new Object[] {"value"})).isTrue();
        assertThat(EmptyValues.isEmpty((byte[]) null)).isTrue();
        assertThat(EmptyValues.isEmpty(new byte[0])).isTrue();
        assertThat(EmptyValues.isNotEmpty(new byte[] {1})).isTrue();
    }

    @Test
    @DisplayName("全部 Unicode 码点的空白判断与 JDK String 保持一致")
    void shouldMatchJdkBlankSemantics_whenCheckingEveryUnicodeCodePoint() {
        int checked = 0;
        for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            char[] codeUnits = Character.toChars(codePoint);
            String text = new String(codeUnits);
            boolean expected = text.isBlank();
            boolean actual = EmptyValues.isBlank(text);
            if (actual != expected) {
                assertThat(actual).as("Unicode U+%04X", codePoint).isEqualTo(expected);
            }
            checked++;
        }
        assertThat(checked).isEqualTo(Character.MAX_CODE_POINT + 1);
    }
}
