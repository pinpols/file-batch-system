package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleAuthApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ConsoleLoginRequest;
import com.example.batch.console.web.response.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.ConsoleAuthTokenResponse;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控制台认证 REST：签发 JWT、查询当前登录主体信息。 */
@RestController
@Validated
@RequestMapping("/api/console/auth")
@RequiredArgsConstructor
public class ConsoleAuthController {

    private final ConsoleAuthApplicationService authApplicationService;
    private final ConsoleResponseFactory responseFactory;

    /** 使用平台库中的控制台账号进行登录并签发 JWT。 */
    @PostMapping("/login")
    public CommonResponse<ConsoleAuthTokenResponse> login(
            @Valid @RequestBody ConsoleLoginRequest request) {
        return responseFactory.success(authApplicationService.login(request));
    }

    /** 为当前已认证用户签发 JWT。 */
    @PostMapping("/token")
    @PreAuthorize("isAuthenticated()")
    public CommonResponse<ConsoleAuthTokenResponse> token(Authentication authentication) {
        return responseFactory.success(authApplicationService.issueToken(authentication));
    }

    /** 当前用户画像（租户、角色等）。 */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public CommonResponse<ConsoleAuthProfileResponse> me(Authentication authentication) {
        return responseFactory.success(authApplicationService.profile(authentication));
    }
}
