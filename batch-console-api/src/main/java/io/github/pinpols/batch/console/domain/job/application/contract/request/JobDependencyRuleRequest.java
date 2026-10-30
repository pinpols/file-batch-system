package io.github.pinpols.batch.console.domain.job.application.contract.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 单条上游依赖边，以及检查上游结果是否生效时使用的业务日期对齐规则。 */
public record JobDependencyRuleRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]{0,63}$")
    String upstreamJobCode,

    @NotBlank @Pattern(regexp = "^(SAME_DAY|PREVIOUS_BIZ_DAY)$")
    String bizDateAlignment) {}
