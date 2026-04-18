package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum NotificationChannelType implements DictEnum {
  EMAIL("EMAIL", "邮件"),
  WEBHOOK("WEBHOOK", "Webhook"),
  DINGTALK("DINGTALK", "钉钉"),
  WECHAT("WECHAT", "企业微信"),
  SMS("SMS", "短信");

  private final String code;
  private final String label;
}
