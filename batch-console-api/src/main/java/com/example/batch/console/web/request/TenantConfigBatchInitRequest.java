package com.example.batch.console.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.util.List;

/**
 * 租户配置批量初始化请求。
 *
 * <p>支持一次性向多个租户推送相同的作业定义、工作流定义、流水线定义、文件通道、文件模板配置。
 * mode=SKIP_EXISTING：已存在则跳过；mode=UPSERT：存在则更新，不存在则插入。
 */
@Data
public class TenantConfigBatchInitRequest {

    /** 目标租户 ID 列表（最多 50 个）。 */
    @NotEmpty(message = "targetTenantIds must not be empty")
    @Size(max = 50, message = "targetTenantIds must not exceed 50")
    private List<@Size(min = 1, max = 64) String> targetTenantIds;

    /** 初始化模式：SKIP_EXISTING 或 UPSERT。默认 SKIP_EXISTING。 */
    private InitMode mode = InitMode.SKIP_EXISTING;

    /** 试运行模式：true 时只做查询和校验，不执行 insert/update/delete。 */
    private boolean dryRun;

    /** 作业定义模板列表。 */
    @Valid private List<JobDefinitionSpec> jobDefinitions;

    /** 工作流定义模板列表。 */
    @Valid private List<WorkflowDefinitionSpec> workflowDefinitions;

    /** 流水线定义模板列表。 */
    @Valid private List<PipelineDefinitionSpec> pipelineDefinitions;

    /** 文件通道模板列表。 */
    @Valid private List<FileChannelSpec> fileChannels;

    /** 文件模板列表。 */
    @Valid private List<FileTemplateSpec> fileTemplates;

    /** 资源队列列表。 */
    @Valid private List<ResourceQueueSpec> resourceQueues;

    /** 批量窗口列表。 */
    @Valid private List<BatchWindowSpec> batchWindows;

    /** 业务日历列表。 */
    @Valid private List<BusinessCalendarSpec> businessCalendars;

    /** 租户配额策略列表。 */
    @Valid private List<TenantQuotaPolicySpec> quotaPolicies;

    /** 告警路由列表。 */
    @Valid private List<AlertRoutingSpec> alertRoutings;

    public enum InitMode {
        /** 已存在则跳过，不存在则创建。 */
        SKIP_EXISTING,
        /** 已存在则更新，不存在则创建。 */
        UPSERT
    }

    // ------------------------------------------------------------------ Spec types

    @Data
    public static class JobDefinitionSpec {
        @Size(max = 128)
        private String jobCode;

        @Size(max = 256)
        private String jobName;

        private String jobType;
        private String bizType;
        private String scheduleType;
        private String scheduleExpr;
        private String timezone;
        private String triggerMode;
        private String workerGroup;
        private String queueCode;
        private String calendarCode;
        private String windowCode;
        private Boolean dagEnabled;
        private String shardStrategy;
        private String retryPolicy;
        private Integer retryMaxCount;
        private Integer timeoutSeconds;
        private String executionHandler;
        private String paramSchema;
        private String defaultParams;
        private Integer priority;
        private Boolean enabled;
        private String description;
    }

    @Data
    public static class WorkflowDefinitionSpec {
        @Size(max = 128)
        private String workflowCode;

        @Size(max = 256)
        private String workflowName;

        private String workflowType;
        private Boolean enabled;
        private List<NodeSpec> nodes;
        private List<EdgeSpec> edges;

        @Data
        public static class NodeSpec {
            private String nodeCode;
            private String nodeName;
            private String nodeType;
            private String relatedJobCode;
            private String relatedPipelineCode;
            private String workerGroup;
            private String windowCode;
            private Integer nodeOrder;
            private String retryPolicy;
            private Integer retryMaxCount;
            private Integer timeoutSeconds;
            private String nodeParams;
            private Boolean enabled;
        }

        @Data
        public static class EdgeSpec {
            private String fromNodeCode;
            private String toNodeCode;
            private String edgeType;
            private String conditionExpr;
            private Boolean enabled;
        }
    }

    @Data
    public static class PipelineDefinitionSpec {
        @Size(max = 128)
        private String jobCode;

        @Size(max = 256)
        private String pipelineName;

        @Size(max = 32)
        private String pipelineType;

        @Size(max = 64)
        private String bizType;

        @Size(max = 128)
        private String workerGroup;

        private Boolean enabled;

        @Size(max = 512)
        private String description;

        private List<StepSpec> steps;

        @Data
        public static class StepSpec {
            private String stepCode;
            private String stepName;
            private String stageCode;
            private Integer stepOrder;
            private String implCode;
            private String stepParams;
            private Integer timeoutSeconds;
            private String retryPolicy;
            private Integer retryMaxCount;
            private Boolean enabled;
        }
    }

    @Data
    public static class FileChannelSpec {
        @Size(max = 128)
        private String channelCode;

        @Size(max = 256)
        private String channelName;

        @Size(max = 32)
        private String channelType;

        @Size(max = 512)
        private String targetEndpoint;

        @Size(max = 32)
        private String authType;

        private String configJson;

        @Size(max = 32)
        private String receiptPolicy;

        private Integer timeoutSeconds;
        private Boolean enabled;
    }

    @Data
    public static class FileTemplateSpec {
        @Size(max = 128)
        private String templateCode;

        @Size(max = 256)
        private String templateName;

        @Size(max = 32)
        private String templateType;

        @Size(max = 64)
        private String bizType;

        @Size(max = 32)
        private String fileFormatType;

        @Size(max = 32)
        private String charset;

        @Size(max = 32)
        private String targetCharset;

        private Boolean withBom;

        @Size(max = 16)
        private String lineSeparator;

        @Size(max = 8)
        private String delimiter;

        @Size(max = 8)
        private String quoteChar;

        @Size(max = 8)
        private String escapeChar;

        private Integer recordLength;
        private Integer headerRows;
        private Integer footerRows;
        private String headerTemplateJson;
        private String trailerTemplateJson;

        @Size(max = 32)
        private String checksumType;

        @Size(max = 32)
        private String compressType;

        @Size(max = 32)
        private String encryptType;

        @Size(max = 512)
        private String namingRule;

        private String fieldMappingsJson;
        private String validationRuleSetJson;

        @Size(max = 128)
        private String defaultQueryCode;

        private String defaultQuerySql;
        private String queryParamSchemaJson;
        private Boolean streamingEnabled;
        private Integer pageSize;
        private Integer fetchSize;
        private Integer chunkSize;
        private Boolean previewMaskingEnabled;
        private Boolean errorLineMaskingEnabled;
        private Boolean logMaskingEnabled;
        private Boolean contentEncryptionEnabled;

        @Size(max = 256)
        private String encryptionKeyRef;

        private Boolean downloadRequiresApproval;
        private String maskingRuleSet;
        private Boolean enabled;
        private Integer version;

        @Size(max = 512)
        private String description;
    }

    @Data
    public static class ResourceQueueSpec {
        @Size(max = 128)
        private String queueCode;

        @Size(max = 256)
        private String queueName;

        @Size(max = 32)
        private String queueType;

        private Integer maxRunningJobs;
        private Integer maxRunningPartitions;
        private Integer maxQps;

        @Size(max = 128)
        private String workerGroup;

        @Size(max = 128)
        private String resourceTag;

        @Size(max = 32)
        private String priorityPolicy;

        private Integer fairShareWeight;
        private Boolean enabled;

        @Size(max = 512)
        private String description;
    }

    @Data
    public static class BatchWindowSpec {
        @Size(max = 128)
        private String windowCode;

        @Size(max = 256)
        private String windowName;

        @Size(max = 64)
        private String timezone;

        @Size(max = 8)
        private String startTime;

        @Size(max = 8)
        private String endTime;

        @Size(max = 32)
        private String endStrategy;

        @Size(max = 32)
        private String outOfWindowAction;

        private Boolean allowCrossDay;
        private Boolean enabled;

        @Size(max = 512)
        private String description;
    }

    @Data
    public static class BusinessCalendarSpec {
        @Size(max = 128)
        private String calendarCode;

        @Size(max = 256)
        private String calendarName;

        @Size(max = 64)
        private String timezone;

        @Size(max = 32)
        private String holidayRollRule;

        @Size(max = 32)
        private String catchUpPolicy;

        private Integer catchUpMaxDays;
        private Boolean enabled;

        /** 节假日列表，可选。格式：yyyy-MM-dd。 */
        private List<String> holidays;
    }

    @Data
    public static class TenantQuotaPolicySpec {
        @Size(max = 128)
        private String policyCode;

        private Integer maxRunningJobsPerTenant;
        private Integer maxPartitionsPerTenant;
        private Integer maxQpsPerTenant;
        private Integer fairShareWeight;
        private Boolean enabled;

        @Size(max = 512)
        private String description;
    }

    @Data
    public static class AlertRoutingSpec {
        @Size(max = 128)
        private String routeCode;

        @Size(max = 256)
        private String routeName;

        @Size(max = 128)
        private String team;

        @Size(max = 128)
        private String alertGroup;

        @Size(max = 32)
        private String severity;

        @Size(max = 512)
        private String receiver;

        @Size(max = 256)
        private String groupBy;

        private Integer groupWaitSeconds;
        private Integer groupIntervalSeconds;
        private Integer repeatIntervalSeconds;
        private Boolean enabled;

        @Size(max = 512)
        private String description;
    }
}
