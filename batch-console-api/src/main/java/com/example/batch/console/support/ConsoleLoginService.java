package com.example.batch.console.support;

import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.request.ConsoleLoginRequest;
import com.example.batch.console.web.response.ConsoleAuthTokenResponse;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 控制台登录服务：从平台库校验账号并签发 JWT。 */
@Service
public class ConsoleLoginService {

    private final ConsoleSecurityProperties securityProperties;
    private final ConsoleJwtService jwtService;
    private final ConsoleSessionRegistry sessionRegistry;
    private final ConsoleUserAccountService userAccountService;
    private final ConsolePasswordHasher passwordHasher;

    public ConsoleLoginService(ConsoleSecurityProperties securityProperties,
                               ConsoleJwtService jwtService,
                               ConsoleSessionRegistry sessionRegistry,
                               ConsoleUserAccountService userAccountService,
                               ConsolePasswordHasher passwordHasher) {
        this.securityProperties = securityProperties;
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
        this.userAccountService = userAccountService;
        this.passwordHasher = passwordHasher;
    }

    public ConsoleAuthTokenResponse login(ConsoleLoginRequest request) {
        if (request == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "login request is required");
        }
        String tenantId = StringUtils.hasText(request.getTenantId()) ? request.getTenantId() : securityProperties.getDefaultTenantId();
        ConsoleUserAccount account = userAccountService.findByTenantAndUsername(tenantId, request.getUsername())
                .orElseThrow(this::invalidCredentials);
        if (!account.enabled()) {
            throw invalidCredentials();
        }
        if (!passwordHasher.matches(request.getPassword(), account.passwordHash())) {
            throw invalidCredentials();
        }
        long sessionVersion = sessionRegistry.nextSessionVersion(account.username(), tenantId);
        return jwtService.issueToken(
                account.username(),
                tenantId,
                new LinkedHashSet<>(account.authorities()),
                sessionVersion
        );
    }

    private BizException invalidCredentials() {
        return new BizException(ResultCode.UNAUTHORIZED, "invalid username or password");
    }
}
