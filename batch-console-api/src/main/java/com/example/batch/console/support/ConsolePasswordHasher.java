package com.example.batch.console.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 控制台密码仅使用 Argon2id（Spring Security 默认参数）。 */
@Component
public class ConsolePasswordHasher {

    private static final Argon2PasswordEncoder ARGON2 =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String encode(String rawPassword) {
        Guard.requireText(rawPassword, "raw password is required");
        return ARGON2.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (!StringUtils.hasText(rawPassword) || !StringUtils.hasText(encodedPassword)) {
            return false;
        }
        if (!encodedPassword.startsWith("$argon2")) {
            throw new BizException(
                    ResultCode.SYSTEM_ERROR,
                    "unsupported password hash format (expected Argon2id)");
        }
        return ARGON2.matches(rawPassword, encodedPassword);
    }
}
