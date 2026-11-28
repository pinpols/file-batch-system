package io.github.pinpols.batch.console.domain.rbac.application.contract.request;

import lombok.Data;

import jakarta.validation.constraints.Size;

@Data
public class UpdateUserAccountRequest {

    @Size(max = 256)
    private String displayName;

    @Size(max = 512)
    private String authoritiesCsv;
}
