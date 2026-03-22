# 结构化日志管道示例

平台当前的统一 MDC 口径是 `service`、`tenantId`、`traceId`、`requestId`、`jobInstanceId`、`fileId`。控制台、调度、触发和 HTTP 型 worker 已统一到同一类 console pattern，便于 Loki / ELK / OpenSearch 做字段解析和跨服务关联。

## 当前日志格式

仓库中的 console pattern 已包含统一字段：

```text
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%X{service:-} %X{tenantId:-} %X{traceId:-} %X{requestId:-} %X{jobInstanceId:-} %X{fileId:-}] %logger{40} - %msg%n
```

## Loki / Promtail 示例

下面是一个和当前 pattern 对齐的 Promtail 示例，只做字段抽取，不改业务日志内容：

```yaml
scrape_configs:
  - job_name: batch-apps
    static_configs:
      - targets: [localhost]
        labels:
          job: batch-apps
          __path__: /var/log/batch/*.log
    pipeline_stages:
      - regex:
          expression: '^(?P<ts>[^ ]+) (?P<level>[^ ]+) \\[(?P<thread>[^]]+)\\] \\[(?P<service>[^ ]*) (?P<tenantId>[^ ]*) (?P<traceId>[^ ]*) (?P<requestId>[^ ]*) (?P<jobInstanceId>[^ ]*) (?P<fileId>[^ ]*)\\] (?P<logger>[^ ]+) - (?P<msg>.*)$'
      - labels:
          service:
          tenantId:
          traceId:
          requestId:
          jobInstanceId:
          fileId:
          level:
      - output:
          source: msg
```

## 约定

- `service` 用于区分控制面和各 worker。
- `tenantId` 用于租户隔离与排障聚合。
- `traceId` 和 `requestId` 用于链路追踪。
- `jobInstanceId` 和 `fileId` 用于把调度、补偿、文件治理日志关联到同一实体。
