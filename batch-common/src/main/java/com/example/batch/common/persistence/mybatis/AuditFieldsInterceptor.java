package com.example.batch.common.persistence.mybatis;

import com.example.batch.common.logging.StructuredLogField;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.MDC;

/**
 * 拦截 MyBatis Executor.update,反射写 entity 的审计字段(createdAt / updatedAt / createdBy / updatedBy /
 * tenantId)。
 *
 * <p>策略:按字段名匹配,**不要求** entity 继承 {@code AuditableEntity};但字段存在且当前为 null 才填(用户显式赋值优先, 避免 Mapper
 * 单测里硬编码时间戳被静默覆盖)。
 *
 * <p>tenantId / operatorId 从 MDC 读(Console-api 由 ConsoleRequestContextFilter 灌入);worker / trigger
 * 后台路径 MDC 空时审计字段保留 null,Mapper xml 仍可显式赋值。
 */
@Intercepts({
  @Signature(
      type = Executor.class,
      method = "update",
      args = {MappedStatement.class, Object.class})
})
public class AuditFieldsInterceptor implements Interceptor {

  private static final String FIELD_CREATED_AT = "createdAt";
  private static final String FIELD_UPDATED_AT = "updatedAt";
  private static final String FIELD_CREATED_BY = "createdBy";
  private static final String FIELD_UPDATED_BY = "updatedBy";
  private static final String FIELD_TENANT_ID = "tenantId";

  // reflection 反射 lookup 缓存,避免每次 SQL 都遍历 declaredFields
  private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

  private final Clock clock;

  public AuditFieldsInterceptor(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object[] args = invocation.getArgs();
    MappedStatement ms = (MappedStatement) args[0];
    Object parameter = args[1];
    SqlCommandType type = ms.getSqlCommandType();
    if (type == SqlCommandType.INSERT || type == SqlCommandType.UPDATE) {
      fillAuditFields(parameter, type);
    }
    return invocation.proceed();
  }

  private void fillAuditFields(Object param, SqlCommandType type) {
    if (param == null) {
      return;
    }
    Instant now = Instant.now(clock);
    String operatorId = MDC.get(StructuredLogField.OPERATOR_ID);
    String tenantId = MDC.get(StructuredLogField.TENANT_ID);
    if (param instanceof Map<?, ?> map) {
      for (Object v : map.values()) {
        fillSingleEntity(v, type, now, operatorId, tenantId);
      }
    } else if (param instanceof Collection<?> col) {
      for (Object v : col) {
        fillSingleEntity(v, type, now, operatorId, tenantId);
      }
    } else {
      fillSingleEntity(param, type, now, operatorId, tenantId);
    }
  }

  private void fillSingleEntity(
      Object entity, SqlCommandType type, Instant now, String operatorId, String tenantId) {
    if (entity == null || isPrimitiveOrWrapper(entity)) {
      return;
    }
    Map<String, Field> fields = resolveFields(entity.getClass());
    if (fields.isEmpty()) {
      return;
    }
    if (type == SqlCommandType.INSERT) {
      setIfNull(entity, fields.get(FIELD_CREATED_AT), now);
      setIfNull(entity, fields.get(FIELD_UPDATED_AT), now);
      setIfNullString(entity, fields.get(FIELD_CREATED_BY), operatorId);
      setIfNullString(entity, fields.get(FIELD_UPDATED_BY), operatorId);
      setIfNullString(entity, fields.get(FIELD_TENANT_ID), tenantId);
    } else {
      // UPDATE: updatedAt / updatedBy 总是刷新(强制最新),createdAt/createdBy 不动
      setForce(entity, fields.get(FIELD_UPDATED_AT), now);
      if (operatorId != null) {
        setForce(entity, fields.get(FIELD_UPDATED_BY), operatorId);
      }
    }
  }

  private boolean isPrimitiveOrWrapper(Object o) {
    Class<?> c = o.getClass();
    return c.isPrimitive()
        || c == String.class
        || c == Long.class
        || c == Integer.class
        || c == Boolean.class
        || c == Double.class
        || c == Float.class
        || c == Short.class
        || c == Byte.class
        || c == Character.class;
  }

  private Map<String, Field> resolveFields(Class<?> clazz) {
    return FIELD_CACHE.computeIfAbsent(
        clazz,
        c -> {
          Map<String, Field> map = new ConcurrentHashMap<>(8);
          Class<?> cursor = c;
          while (cursor != null && cursor != Object.class) {
            for (Field f : cursor.getDeclaredFields()) {
              String name = f.getName();
              if (FIELD_CREATED_AT.equals(name)
                  || FIELD_UPDATED_AT.equals(name)
                  || FIELD_CREATED_BY.equals(name)
                  || FIELD_UPDATED_BY.equals(name)
                  || FIELD_TENANT_ID.equals(name)) {
                f.setAccessible(true);
                map.putIfAbsent(name, f);
              }
            }
            cursor = cursor.getSuperclass();
          }
          return map;
        });
  }

  private void setIfNull(Object entity, Field f, Instant value) {
    if (f == null || value == null) {
      return;
    }
    try {
      if (f.get(entity) == null) {
        f.set(entity, value);
      }
    } catch (IllegalAccessException ignore) {
      // setAccessible(true) 已设置;走到这里说明 SecurityManager 拒绝,静默跳过保证 SQL 仍能走
    }
  }

  private void setIfNullString(Object entity, Field f, String value) {
    if (f == null || value == null || value.isBlank()) {
      return;
    }
    try {
      if (f.get(entity) == null) {
        f.set(entity, value);
      }
    } catch (IllegalAccessException ignore) {
    }
  }

  private void setForce(Object entity, Field f, Object value) {
    if (f == null || value == null) {
      return;
    }
    try {
      f.set(entity, value);
    } catch (IllegalAccessException ignore) {
    }
  }
}
