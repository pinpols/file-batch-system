package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 控制台登录请求：用户名 + 密码（租户从账号记录自动获取）。 */
@Data
public class ConsoleLoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
