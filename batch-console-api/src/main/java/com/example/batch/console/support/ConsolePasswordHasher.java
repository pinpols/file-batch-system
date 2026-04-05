package com.example.batch.console.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 控制台密码哈希器：
 * - 新密码默认使用 Argon2id
 * - 兼容历史 PBKDF2-SHA256 存量密码
 */
@Component
public class ConsolePasswordHasher {

    private static final String PBKDF2_SCHEME = "pbkdf2_sha256";
    private static final int KEY_LENGTH_BITS = 256;
    private static final Argon2PasswordEncoder ARGON2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String encode(String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "raw password is required");
        }
        return ARGON2.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (!StringUtils.hasText(rawPassword) || !StringUtils.hasText(encodedPassword)) {
            return false;
        }
        if (encodedPassword.startsWith("$argon2")) {
            return ARGON2.matches(rawPassword, encodedPassword);
        }
        if (encodedPassword.startsWith(PBKDF2_SCHEME + "$")) {
            return matchesPbkdf2(rawPassword, encodedPassword);
        }
        throw new BizException(ResultCode.SYSTEM_ERROR, "unsupported password hash format");
    }

    private boolean matchesPbkdf2(String rawPassword, String encodedPassword) {
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != 4 || !PBKDF2_SCHEME.equals(parts[0])) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "unsupported password hash format");
        }
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = derivePbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] derivePbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", exception);
        }
    }
}
