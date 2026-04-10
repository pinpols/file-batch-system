package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NotificationChannelUpsertRequest {

    @NotBlank
    @Size(max = 64)
    private String channelCode;

    @NotBlank
    @Size(max = 128)
    private String channelName;

    @NotBlank
    @Size(max = 32)
    private String channelType;

    private String configJson;

    private Boolean enabled = Boolean.TRUE;
}
