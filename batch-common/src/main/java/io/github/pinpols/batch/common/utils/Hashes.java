package io.github.pinpols.batch.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.annotation.Nullable;

import io.github.pinpols.batch.common.security.CryptoAlgorithms;

/**
 * 通用哈希工具:抹掉重复的 SHA-256 + hex 编码样板。
 *
 * <p>典型用途:
 *
 * <ul>
 *   <li>审计 / 日志里把 IP / UA 等弱敏感字段做短哈希(8 字节 / 16 hex)写入,既能 drift 比对又不直接落明文
 *   <li>JWT IP/UA binding hash(参考 {@code ConsoleJwtService#sha256Short})
 *   <li>幂等键、快照指纹与按协议要求构造的 SHA-256 摘要(短信签名、OIDC cookie 名等)
 * </ul>
 *
 * <p>使用边界:
 *
 * <ul>
 *   <li>{@link #sha256Short} 会截断到 8 字节,只用于审计 / drift 比对等"防碰撞需求低"的场景,<b>不得</b>用于签名、令牌或任何安全判定
 *   <li>{@link #sha256Hex} 是完整 SHA-256,可用于协议规定使用 SHA-256 十六进制的场景(短信签名、指纹);密码存储仍须使用专门的 KDF,不能直接用本方法
 * </ul>
 */
public final class Hashes {

    private Hashes() {}

    /**
     * SHA-256 后取前 8 字节,返回 16 字符小写 hex 字符串。null / 空入参 → null。
     *
     * <p>SHA-256 算法不可用时(理论不会发生)返回 null,调用方需做 null-safe 处理。
     */
    @Nullable
    public static String sha256Short(@Nullable String raw) {
        if (EmptyValues.isEmpty(raw)) {
            return null;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance(CryptoAlgorithms.SHA_256);
            byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
            byte[] full = sha256.digest(rawBytes);
            byte[] head = new byte[8];
            System.arraycopy(full, 0, head, 0, 8);
            return HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** SHA-256 后按小写 hex 输出完整摘要,用于幂等键、快照指纹等需要完整长度的比对。 */
    public static String sha256Hex(String raw) {
        return sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 字节形态的 SHA-256 小写 hex;文件内容或已序列化载荷直接传入,避免再包一层字符串。 */
    public static String sha256Hex(byte[] raw) {
        return sha256Hex(raw, SHA_256_FACTORY);
    }

    /** 摘要工厂可注入,仅用于覆盖「算法不可用」的失败分支。 */
    static String sha256Hex(byte[] raw, Sha256Factory factory) {
        try {
            return HexFormat.of().formatHex(factory.create().digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @FunctionalInterface
    interface Sha256Factory {
        MessageDigest create() throws NoSuchAlgorithmException;
    }

    private static final Sha256Factory SHA_256_FACTORY = () -> MessageDigest.getInstance(CryptoAlgorithms.SHA_256);
}
