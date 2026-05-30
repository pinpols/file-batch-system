package com.example.batch.console.domain.job.web.request;

import com.example.batch.common.validation.ValidBizDate;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RerunRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "jobCode too long (max 128)")
  private String jobCode;

  @NotBlank @ValidBizDate private String bizDate;
  private Long targetId;
  private String targetInstanceNo;
  private String batchNo;
  private Long relatedFileId;
  private String reason;
  private String operatorId;
  private String approvalId;
  private String strategy;

  /**
   * 补跑结果版本策略（§5.5）。可空 — 默认走 {@code CREATE_NEW_VERSION}。
   *
   * <ul>
   *   <li>{@code CREATE_NEW_VERSION} —— 默认；新 runAttempt + 新输出版本，历史保留
   *   <li>{@code KEEP_BOTH} —— 同时保留新旧结果，由下游消费者自行选择
   *   <li>{@code MANUAL_CONFIRM_EFFECTIVE} —— 写入但不生效，需运维显式 confirm 才推上"effective"
   * </ul>
   */
  @Pattern(
      regexp = "^(CREATE_NEW_VERSION|KEEP_BOTH|MANUAL_CONFIRM_EFFECTIVE)$",
      message = "resultPolicy must be CREATE_NEW_VERSION / KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE")
  private String resultPolicy;

  /**
   * 补跑配置版本策略（§5.5）。可空 — 默认走 {@code USE_ORIGINAL_CONFIG}。
   *
   * <ul>
   *   <li>{@code USE_ORIGINAL_CONFIG} —— 默认；用原 instance 的 job_definition_version 回放
   *   <li>{@code USE_LATEST_CONFIG} —— 用当前 enabled 的最新 job_definition 配置
   *   <li>{@code USE_SPECIFIED_VERSION} —— 用 {@link #configVersion} 指定的历史版本
   * </ul>
   */
  @Pattern(
      regexp = "^(USE_ORIGINAL_CONFIG|USE_LATEST_CONFIG|USE_SPECIFIED_VERSION)$",
      message =
          "configVersionPolicy must be USE_ORIGINAL_CONFIG / USE_LATEST_CONFIG /"
              + " USE_SPECIFIED_VERSION")
  private String configVersionPolicy;

  /** 仅当 {@link #configVersionPolicy} == USE_SPECIFIED_VERSION 时生效。 */
  @Positive(message = "configVersion must be positive when provided")
  private Integer configVersion;
}
