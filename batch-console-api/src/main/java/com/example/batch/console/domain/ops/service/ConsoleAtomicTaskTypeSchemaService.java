package com.example.batch.console.domain.ops.service;

import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema.ParamSpec;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema.SecurityGate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 平台内置原子任务四类(sql / shell / stored_proc / http)的参数 schema + 安全闸静态目录。
 *
 * <p>console-api 不依赖 {@code batch-worker-atomic},故以静态镜像维护;字段与各 {@code *TaskExecutor.PARAM_*} +
 * {@code *ExecutorProperties} 对齐(ADR-029)。worker 侧改字段须同步本目录(FE 表单单一权威源)。
 */
@Service
public class ConsoleAtomicTaskTypeSchemaService {

  private static final String STRING = "string";
  private static final String NUMBER = "number";
  private static final String BOOLEAN = "boolean";
  private static final String LIST = "list";
  private static final String MAP = "map";

  private static final List<AtomicTaskTypeSchema> CATALOG =
      List.of(
          new AtomicTaskTypeSchema(
              "sql",
              "SQL 执行",
              true,
              List.of(
                  new ParamSpec("sql", STRING, true, "要执行的 SQL(非空)"),
                  new ParamSpec("dataSourceBean", STRING, false, "目标数据源 bean 名(受白名单限定)"),
                  new ParamSpec("statementTimeoutSeconds", NUMBER, false, "语句超时秒数"),
                  new ParamSpec("autoCommit", BOOLEAN, false, "是否自动提交")),
              List.of(
                  new SecurityGate("allowedDataSourceBeans", "数据源白名单,空=仅 dev 全放行"),
                  new SecurityGate("forbidOsCapableRole", "禁止使用带 OS 能力的 DB 角色"),
                  new SecurityGate("maxResultRows", "结果集行数上限,超出截断"))),
          new AtomicTaskTypeSchema(
              "stored_proc",
              "存储过程调用",
              true,
              List.of(
                  new ParamSpec("procedureName", STRING, true, "存储过程名(受 allowedSchemas 限定)"),
                  new ParamSpec("inParams", LIST, false, "入参,有序列表"),
                  new ParamSpec("outParams", LIST, false, "出参 SQL 类型列表"),
                  new ParamSpec("statementTimeoutSeconds", NUMBER, false, "语句超时秒数"),
                  new ParamSpec("dataSourceBean", STRING, false, "目标数据源 bean 名(受白名单限定)"),
                  new ParamSpec("autoCommit", BOOLEAN, false, "是否自动提交")),
              List.of(
                  new SecurityGate("allowedSchemas", "schema 白名单(默认 schema=batch)"),
                  new SecurityGate("allowedDataSourceBeans", "数据源白名单"),
                  new SecurityGate("forbidOsCapableRole", "禁止使用带 OS 能力的 DB 角色"),
                  new SecurityGate(
                      "allowSecurityDefiner", "是否允许 SECURITY DEFINER 过程(默认禁,防借 owner 提权)"))),
          new AtomicTaskTypeSchema(
              "shell",
              "Shell 命令",
              false,
              List.of(
                  new ParamSpec("command", STRING, true, "命令(须在 commandWhitelist 内)"),
                  new ParamSpec("args", LIST, false, "参数,字符串列表"),
                  new ParamSpec("timeoutSeconds", NUMBER, false, "超时秒数"),
                  new ParamSpec("env", MAP, false, "环境变量,仅透传 allowedEnvKeys 内的 key")),
              List.of(
                  new SecurityGate("commandWhitelist", "命令白名单(默认空=全禁,需平台配置)"),
                  new SecurityGate("workdirBase", "临时工作目录隔离根"),
                  new SecurityGate("allowedEnvKeys", "可透传环境变量名白名单"),
                  new SecurityGate("argRegexAllowlist", "参数正则白名单"),
                  new SecurityGate("rejectParentDirRefs", "拒绝包含上级目录引用(../)的参数"))),
          new AtomicTaskTypeSchema(
              "http",
              "HTTP 调用",
              true,
              List.of(
                  new ParamSpec("url", STRING, true, "目标 URL(受 host 白/黑名单 + SSRF 校验)"),
                  new ParamSpec("method", STRING, false, "HTTP 方法(默认 GET,受 allowedMethods 限定)"),
                  new ParamSpec("headers", MAP, false, "请求头"),
                  new ParamSpec("body", STRING, false, "请求体"),
                  new ParamSpec("timeoutSeconds", NUMBER, false, "超时秒数"),
                  new ParamSpec("expectStatus", NUMBER, false, "期望响应状态码"),
                  new ParamSpec("auth", MAP, false, "鉴权配置(受 allowedAuthTypes 限定)")),
              List.of(
                  new SecurityGate("allowedHostPatterns", "出口域名白名单(空=仅 dev 全放行)"),
                  new SecurityGate("blockedHostPatterns", "出口域名黑名单(优先于白名单,默认拒 metadata/localhost)"),
                  new SecurityGate("blockPrivateIps", "拦截私网/环回/链路本地 IP(SSRF 防御)"),
                  new SecurityGate("allowedMethods", "HTTP 方法白名单"),
                  new SecurityGate("allowedAuthTypes", "鉴权类型白名单"))));

  /** 返回四类内置原子任务的 schema(只读静态目录)。 */
  public List<AtomicTaskTypeSchema> schema() {
    return CATALOG;
  }
}
