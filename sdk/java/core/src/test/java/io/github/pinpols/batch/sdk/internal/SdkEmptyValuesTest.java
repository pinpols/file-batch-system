package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SdkEmptyValues — 与平台一致的空/空白与集合语义")
class SdkEmptyValuesTest {

    @Test
    @DisplayName("文本: 空串、纯空白与 hasText/isBlank 的反向语义")
    void shouldDistinguishEmptyBlankAndText() {
        assertThat(SdkEmptyValues.isEmpty((String) null)).isTrue();
        assertThat(SdkEmptyValues.isEmpty("")).isTrue();
        assertThat(SdkEmptyValues.isEmpty(" ")).isFalse();
        assertThat(SdkEmptyValues.isNotEmpty("value")).isTrue();
        assertThat(SdkEmptyValues.isBlank(" ")).isTrue();
        assertThat(SdkEmptyValues.isBlank("value")).isFalse();
        assertThat(SdkEmptyValues.isNotBlank("value")).isTrue();
        assertThat(SdkEmptyValues.hasText(" ")).isFalse();
        assertThat(SdkEmptyValues.hasText("value")).isTrue();
        assertThat(SdkEmptyValues.hasText((String) null)).isFalse();
    }

    @Test
    @DisplayName("集合、Map 与数组: null 与零元素都算空")
    void shouldHandleNullAndEmptyCollections() {
        assertThat(SdkEmptyValues.isEmpty((List<?>) null)).isTrue();
        assertThat(SdkEmptyValues.isEmpty(List.of())).isTrue();
        assertThat(SdkEmptyValues.isEmpty(List.of("value"))).isFalse();
        assertThat(SdkEmptyValues.isNotEmpty(List.of("value"))).isTrue();
        assertThat(SdkEmptyValues.isEmpty((Map<?, ?>) null)).isTrue();
        assertThat(SdkEmptyValues.isEmpty(Map.of())).isTrue();
        assertThat(SdkEmptyValues.isNotEmpty(Map.of("key", "value"))).isTrue();
        assertThat(SdkEmptyValues.isEmpty((byte[]) null)).isTrue();
        assertThat(SdkEmptyValues.isEmpty(new byte[0])).isTrue();
        assertThat(SdkEmptyValues.isNotEmpty(new byte[] {1})).isTrue();
        assertThat(SdkEmptyValues.isEmpty((Object[]) null)).isTrue();
        assertThat(SdkEmptyValues.isNotEmpty(new Object[] {"value"})).isTrue();
    }
}
