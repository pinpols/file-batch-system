package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通用哈希工具: 完整摘要的固定向量、字节与字符串等价、小写十六进制形态")
class HashesTest {

    @Test
    @DisplayName("标准向量: 空串与 abc 的 SHA-256 小写 hex 与公开向量一致")
    void shouldMatchKnownVectors() {
        assertThat(Hashes.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Hashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("非 ASCII 输入: 按 UTF-8 编码计算，与字节重载结果一致")
    void shouldHashUtf8BytesConsistently() {
        String value = "批量作业";
        assertThat(Hashes.sha256Hex(value))
                .isEqualTo("b24e98c3332fcb1790e6e8f697106729dc5e1809e260c2be023375bca334bc39");
        assertThat(Hashes.sha256Hex(value.getBytes(StandardCharsets.UTF_8))).isEqualTo(Hashes.sha256Hex(value));
    }

    @Test
    @DisplayName("输出形态: 固定 64 字符小写十六进制，不含分隔符")
    void shouldAlwaysEmitLowercaseHex() {
        assertThat(Hashes.sha256Hex("any")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(Hashes.sha256Hex(new byte[] {1, 2, 3})).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("短哈希与完整摘要: 短哈希是完整摘要的前 16 个字符")
    void shouldKeepShortHashAsPrefixOfFullDigest() {
        assertThat(Hashes.sha256Short("abc")).isEqualTo(Hashes.sha256Hex("abc").substring(0, 16));
    }
}
