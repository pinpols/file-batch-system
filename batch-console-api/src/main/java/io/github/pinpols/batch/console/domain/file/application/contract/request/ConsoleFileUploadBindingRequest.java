package io.github.pinpols.batch.console.domain.file.application.contract.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 人工文件上传的可选文件组绑定；缺省时维持普通单文件上传语义。 */
public record ConsoleFileUploadBindingRequest(
    @NotBlank @Size(max = 128) String calendarCode,
    @NotNull @Positive Long workflowDefinitionId,
    @NotBlank @Size(max = 128) String fileGroupCode,
    @NotNull LocalDate bizDate,
    @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 255) String> requiredFileNames,
    @NotBlank @Size(max = 128) String bundleTemplateCode) {}
