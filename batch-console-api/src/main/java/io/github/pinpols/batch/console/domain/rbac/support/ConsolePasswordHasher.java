package io.github.pinpols.batch.console.domain.rbac.support;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.common.utils.Preconditions;

/** 控制台密码仅使用 Argon2id（Spring Security 默认参数）。 */
@Component
public class ConsolePasswordHasher {

    private static final Argon2PasswordEncoder ARGON2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String encode(String rawPassword) {
        Preconditions.requireText(rawPassword, "raw password is required");
        return ARGON2.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (!EmptyValues.hasText(rawPassword) || !EmptyValues.hasText(encodedPassword)) {
            return false;
        }
        if (!encodedPassword.startsWith("$argon2")) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "error.auth.unsupported_password_hash");
        }
        return ARGON2.matches(rawPassword, encodedPassword);
    }
}
