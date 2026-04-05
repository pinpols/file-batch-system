package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 最小登录请求：固定账号 + 密码，tenantId 可选。 */
@Data
public class ConsoleLoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @ValidTenantId
    private String tenantId;
}
