package com.example.batch.console.mapper;

import com.example.batch.console.support.audit.OperationAuditEvent;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 通用控制台操作审计 mapper。INSERT 由 Aspect 同事务触发,查询走 ConsoleQueryController。 */
@Mapper
public interface OperationAuditMapper {

  /** 同事务 INSERT(Aspect 调用)。失败抛异常,Aspect 自己决定是否吃掉(默认吃掉避免污染业务事务)。 */
  int insert(@Param("e") OperationAuditEvent event);

  /**
   * 分页查询。所有参数可空,空 = 不过滤。按 created_at DESC + id DESC 稳定排序。
   *
   * <p>**注意**:offset 大时性能差;UI 默认 pageSize=15,且 console_operation_audit 量级不会超 千万,常规分页够用。后续真有热查询场景再加
   * (action, created_at DESC) 复合索引并改 keyset pagination。
   */
  // mapper 多条件分页查询参数列表必要,@Param 一一对应 mybatis xml #{xxx},不可包装成 Param 对象。
  @SuppressWarnings("PMD.ExcessiveParameterList")
  List<AuditRow> query(
      @Param("tenantId") String tenantId,
      @Param("aggregateType") String aggregateType,
      @Param("aggregateId") String aggregateId,
      @Param("action") String action,
      @Param("operatorId") String operatorId,
      @Param("result") String result,
      @Param("traceId") String traceId,
      @Param("startTime") Instant startTime,
      @Param("endTime") Instant endTime,
      @Param("offset") int offset,
      @Param("limit") int limit);

  /** 跟 query 同条件,只数总数。给 UI 算总页数用。 */
  // 同 query 的参数集（共 9 个),用同一组 @Param 实现 count 查询,不可包装。
  @SuppressWarnings("PMD.ExcessiveParameterList")
  long count(
      @Param("tenantId") String tenantId,
      @Param("aggregateType") String aggregateType,
      @Param("aggregateId") String aggregateId,
      @Param("action") String action,
      @Param("operatorId") String operatorId,
      @Param("result") String result,
      @Param("traceId") String traceId,
      @Param("startTime") Instant startTime,
      @Param("endTime") Instant endTime);

  /**
   * 查询行,DTO 直接对应表结构。这里**不**复用 OperationAuditEvent,因为:
   *
   * <ol>
   *   <li>查询路径需要 id + createdAt(写路径 createdAt 是 DB default,id 是 BIGSERIAL,Java 侧不 关心)
   *   <li>查询返回不应携带写入端的 default 字段
   * </ol>
   */
  record AuditRow(
      Long id,
      String tenantId,
      String aggregateType,
      String aggregateId,
      String action,
      String operatorId,
      String operatorRole,
      String result,
      String errorCode,
      String errorMessage,
      String paramsJson,
      String traceId,
      String requestId,
      String ipHash,
      String uaHash,
      Integer eventVersion,
      Instant createdAt) {}
}
