package com.example.batch.console.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.batch.console.support.ConsoleRoles;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "batch.console.ai")
public class ConsoleAiProperties {

    private boolean enabled = false;
    private String model = "gpt-4o-mini";
    private int maxPromptLength = 4000;
    private int maxResponseLength = 3000;
    private List<String> allowedUsers = new ArrayList<>(List.of("admin"));
    private List<String> allowedAuthorities =
            new ArrayList<>(List.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR));
    private List<String> domainKeywords =
            new ArrayList<>(
                    List.of(
                            "batch",
                            "workflow",
                            "job",
                            "instance",
                            "partition",
                            "task",
                            "file",
                            "dispatch",
                            "import",
                            "export",
                            "orchestrator",
                            "trigger",
                            "worker",
                            "pipeline",
                            "dag",
                            "retry",
                            "dead letter",
                            "dead-letter",
                            "archive",
                            "governance",
                            "console",
                            "audit",
                            "reconcile",
                            "归档",
                            "重分发",
                            "工作流",
                            "文件",
                            "调度",
                            "导入",
                            "导出",
                            "重试",
                            "死信",
                            "节点",
                            "分片"));
    private List<String> blockedKeywords =
            new ArrayList<>(
                    List.of(
                            "password",
                            "api key",
                            "api-key",
                            "secret",
                            "token",
                            "system prompt",
                            "system prompt",
                            "密钥",
                            "密码",
                            "口令",
                            "私钥"));
}
