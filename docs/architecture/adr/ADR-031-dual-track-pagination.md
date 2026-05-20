# ADR-031: 双轨分页(Offset + Cursor)

- 状态: Proposed (2026-05-20)
- 范围: `batch-console-api` 所有列表 API + FE `batch-console` pagination 组件
- 影响:21 个 mapper(实际 59 处 selectByQuery/countByQuery 调用)+ FE ProTable / el-pagination

## 范围边界

✅ **做**:在现有 offset 分页之上**叠加** cursor 模式,**双轨并存**;按表特征选默认模式。
❌ **不做**:全量替换 offset 为 cursor;改变现有 offset 端点的 API 契约;搞通用「ORM 级」分页框架。

---

## 1. 背景 / 现状

### 现状盘点

- console-api 共 **21 个 mapper** 实现 LIMIT/OFFSET 分页(`SELECT ... LIMIT #{pageSize} OFFSET #{offset}`)
- 配套 `PageRequest` (pageNo, pageSize) + `PageResponse` (items, total, pageNo, pageSize)
- 每个 mapper 配对 `selectByQuery` + `countByQuery`(手写)
- FE 用 Element Plus `el-pagination` 组件,支持跳页 + 显示总数

### 真实驱动力(为什么现在动)

> ❌ **不是**「offset 性能差」。slow query log 没显示问题,内部控制台单租户过滤后行数可控。
>
> ✅ **是**两个特定问题:
>
> 1. **移动端 infinite scroll 需要 cursor**:`MJobInstances` / `MAlerts` / `MOutbox` 等列表当前用 offset + 触底分页拼接,**遇到并发写时会漏/重**(2026-05-15 已观察到一次告警漏显示)
> 2. **大表深翻页有 latent 风险**:`job_step_instance` / `workflow_node_run` 在 ~6 个月数据保留期里会达百万行,运维若深翻 page 1000+ 已经能感受到 1s+ 延迟。还没炸,但可预见

### 不引插件(再申明)

- PageHelper 的 ThreadLocal 黑魔法 + count 自动改写易出错,大厂复盘已弃用,详见 ADR-031.附录 A
- MyBatis-Plus 等于换 ORM,违反 ADR-001「全模块统一 MyBatis」
- 自写薄封装 ~80 LOC,完全够用

---

## 2. 设计原则

### 分页模式选择(per-table 决定,不是 per-endpoint)

| 表特征 | 默认模式 | 是否同时暴露另一种 |
|---|---|---|
| 大表 + 时间序 + 主要倒序看新数据 | **cursor** | 暴露 offset(运营跳页时用) |
| 配置 / 定义 / 元数据(< 10k 行) | **offset** | 不暴露 cursor(无收益) |
| 移动端列表 | **cursor**(infinite scroll) | 桌面端可同端点走 offset |

判定提问:**「用户/运营会跳到第 N 页吗?」**
- 会 → offset 必须有
- 不会(永远只是「往下翻 / 往新看」)→ cursor 主推,offset 不必要

### Cursor 形态约束

1. **不透明 token**:base64(JSON({sortKeys}))。用户不可解读,版本可演进。
2. **租户 / 权限谓词永远在 WHERE 第一位**,cursor 谓词追加,**不能让 cursor 解码出的 id 影响租户隔离**(否则有跨租户穿透洞)。
3. **排序键必须包含主键(id)做 tiebreaker**:`order by created_at desc, id desc` —— 防止同 `created_at` 多行翻页漏数据。
4. **失效不报错,降级**:cursor 解码失败或排序键已被删,返回空 + `nextCursor=null`,不抛 500。
5. **不返 total**:cursor 模式响应不带 `total`。要总数走 offset 模式(或单独 count 端点)。

---

## 3. 端点分类(21 → cursor 10 + offset 11)

### Group A:cursor 主推(10 个 mapper)

| Mapper | 表 | 排序键 | 备注 |
|---|---|---|---|
| **JobInstanceMapper** | `job_instance` | `(id desc)` | 主任务执行表,移动端已痛 |
| **JobStepInstanceMapper** | `job_step_instance` | `(id desc)` | step 数 ≈ 任务数 × 10 |
| **JobPartitionMapper** | `job_partition` | `(id desc)` | 同上 |
| **WorkflowRunMapper** | `workflow_run` | `(id desc)` | 工作流执行历史 |
| **WorkflowNodeRunMapper** | `workflow_node_run` | `(id desc)` | 比 run 多 |
| **AlertEventMapper** | `alert_event` | `(triggered_at desc, id desc)` | 复合 cursor;按时间倒序看新告警 |
| **AuditLogMapper** | `console_operation_audit` | `(operated_at desc, id desc)` | 审计日志,时间序 |
| **DeadLetterTaskMapper** | `dead_letter_task` | `(id desc)` | 失败任务 |
| **PendingCatchUpMapper** | `pending_catch_up` | `(id desc)` | 补单队列 |
| **RetryScheduleMapper** | `retry_schedule` | `(next_run_at asc, id asc)` | 复合 cursor;按下次执行时间正序 |

**理由**:这些表都有 `保留期内累积无上限` 特征,任何一张到 100w+ 行后 offset 翻深页都会慢。

### Group B:offset 保留(11 个 mapper)

| Mapper | 行数级别 | 为什么不要 cursor |
|---|---|---|
| JobDefinitionMapper | < 1000/租户 | 运营要跳页找作业 |
| PipelineDefinitionMapper | < 100/租户 | 同上,且要看总数 |
| BatchDayMapper | 365/年 | 日历,小 |
| BatchWindowMapper | 配置 | 几十行 |
| BusinessCalendarMapper | 配置 | 几十行 |
| ConsoleUserAccountMapper | < 1000 | 用户管理 |
| TenantMapper | < 100 | 租户管理 |
| TenantQuotaPolicyMapper | 配置 | 配额规则,小 |
| ResourceQueueMapper | 配置 | 队列定义,< 50 |
| WorkerRegistryMapper | < 100 | Worker 节点 |
| ApprovalCommandMapper | 中等 | 审批列表,要跳页 + 总数 |

**理由**:小表 offset 性能完全够,跳页 + 总数是必需 UX。

---

## 4. 抽象设计

### BE 公共件(`batch-common`)

**4.1 sealed PageQuery**(请求统一):
```java
public sealed interface PageQuery {
  int pageSize();

  record Offset(int pageNo, int pageSize) implements PageQuery {
    public long offset() { return (long)(pageNo - 1) * pageSize; }
  }
  record Cursor(String token, int pageSize) implements PageQuery {}

  static PageQuery of(Integer pageNo, String cursor, int pageSize) {
    return cursor != null && !cursor.isBlank()
        ? new Cursor(cursor, pageSize)
        : new Offset(pageNo == null || pageNo < 1 ? 1 : pageNo, pageSize);
  }
}
```

**4.2 PagedResult**(响应统一):
```java
public record PagedResult<T>(
    List<T> items,
    Long total,           // offset only
    Integer pageNo,       // offset only
    String nextCursor,    // cursor only
    boolean hasMore       // 通用
) {
  public static <T> PagedResult<T> offset(List<T> items, long total, int pageNo);
  public static <T> PagedResult<T> cursor(List<T> items, String next, boolean hasMore);
}
```

**4.3 CursorCodec**:
```java
public final class CursorCodec {
  public static String encode(Map<String, Object> sortKey);
  public static Map<String, Object> decode(String token); // 解错返 empty map,不抛
}
```

**4.4 Mapper xml 公共片段**(`common-page.xml`,放 `batch-common/src/main/resources/mapper/`):
```xml
<sql id="limit-clause">
  limit #{page.pageSize}
  <if test="page instanceof @com.example.batch.common.page.PageQuery$Offset">
    offset #{page.offset}
  </if>
</sql>
```

### FE 公共件(`batch-console/src/components/common`)

**4.5 ProPagination 组件**:
```vue
<ProPagination
  :mode="paginationMode"      <!-- 'page' | 'cursor' -->
  v-model:page-no="pageNo"
  v-model:cursor="cursor"
  :total="total"
  :has-more="hasMore"
  :page-size="pageSize"
  @change="reload"
/>
```
- `mode='page'` → 渲染 `el-pagination`
- `mode='cursor'` → 渲染「上一页 / 下一页」按钮 + 当前位置指示

**4.6 usePagination composable**:
```ts
const {
  pageNo, cursor, hasMore, total,
  apiParams,       // { pageNo, pageSize } 或 { cursor, pageSize }
  applyResponse,   // 收到响应自动消化
  reset,           // 切租户/筛选时调用
} = usePagination({ mode: 'cursor', pageSize: 20 })
```

---

## 5. 实施计划

### 5.0 阶段 0:offset 公共片段抽取(✅ 已完成 2026-05-20)

**纯重构,无行为变更**。把 21 个 mapper.xml 里完全相同的 5 行 offset bind 抽到
`batch-common/src/main/resources/mapper/CommonFragments.xml` 的 `<sql id="offsetPageClause">`,各 mapper 末尾 `<include refid="com.example.batch.common.mapper.CommonFragments.offsetPageClause"/>`。
减少 ~80 LOC 重复,MyBatis 跨命名空间 include 标准能力,零运行时开销。

### 5.1 阶段 1:基础设施(预计 1.5d)

- [ ] BE `batch-common` 加 PageQuery / PagedResult / CursorCodec + 单测
- [ ] BE mapper xml `common-page.xml` 片段
- [ ] FE `src/components/common/ProPagination.vue` + `usePagination.ts`
- [ ] FE `src/api/pagination.ts` 类型 + 工具

### 5.2 阶段 2:Pilot 端点(预计 0.5d)

**选 `/queries/instances`(job_instance)做首发**。理由:
- 单租户大表,真实需要
- 简单排序 `order by id desc`,cursor 形态最干净
- 移动端 `MJobInstances` 已痛,需求最迫切
- 影响面可控:1 mapper + 1 controller + 1 service + FE 2 个 list 页(桌面 + 移动)

Pilot 验收:
- `?cursor=` 模式翻页准确,移动端 infinite scroll 不漏不重
- `?pageNo=` 模式仍然工作(向后兼容)
- 跨租户测试:cursor 解码出的 lastId 不能查到别租户数据
- E2E 覆盖:`e2e/job-instance-list.spec.ts` + `e2e/m-jobs-list.spec.ts`

### 5.3 阶段 3:批量推广(预计 3d,分 3 个 commit)

按表分批,每批 commit 一次,模块单测每批跑一次:

**Batch 1**(simple cursor,id desc):JobInstance / JobStepInstance / JobPartition / WorkflowRun / WorkflowNodeRun / DeadLetterTask / PendingCatchUp(7 个)

**Batch 2**(复合 cursor,时间 + id):AlertEvent / AuditLog / RetryScheduleMapper(3 个)

**Batch 3**(暴露双轨):上述 10 个端点 API 同时接 `pageNo` 和 `cursor`,FE list 页根据 viewport / 用户偏好选模式

### 5.4 阶段 4:FE 推广(预计 1.5d)

- 移动端 list 页(`MJobInstances` / `MAlerts` / `MOutbox` 等 5+ 页)全切 cursor + infinite scroll
- 桌面端默认仍 offset,Group A 端点提供「切换无限滚动」开关(隐藏在每个列表页右上,默认关)

### 5.5 阶段 5:文档 + 守护(0.5d)

- 更新 `docs/api/console-api-protocol.md` Changelog
- OpenAPI yaml:Group A 端点参数 + 响应 schema 双轨
- 加守护测试 `CursorPaginationConsistencyTest`:同样数据 cursor 翻完 = offset 翻完(items 顺序、数量一致)

**合计预算**: 7 人天(含联调缓冲)

---

## 6. 安全 / 性能注意

### 6.1 租户穿透防护(P0)

cursor 是用户可控输入。SQL 必须保证:
```xml
<select ...>
  ...
  where tenant_id = #{tenantId}    <!-- 永远第一位 -->
    ...other filters...
  <if test="page instanceof Cursor">
    and id &lt; #{page.lastId}     <!-- cursor 谓词在租户过滤之后 -->
  </if>
  order by id desc
  limit #{page.pageSize}
</select>
```
**绝不允许** cursor 谓词包含 `tenant_id` 比较,防止用户构造 cursor 跨租户取数据。

### 6.2 索引要求

每个 cursor 化的表必须有覆盖排序键的索引:
- `id desc`:主键索引天然支持
- `(triggered_at desc, id desc)`:需要 `CREATE INDEX ... (tenant_id, triggered_at desc, id desc)` 复合索引
- 缺索引会让 cursor 比 offset 还慢

迁移 SQL(V131+)在阶段 3 同步追加,索引上线后才切端点。

### 6.3 cursor 解码失败语义

- token 损坏 → 返回空列表 + `nextCursor=null`,**不返 400**(用户体验是「这一页没数据」)
- token 解码出的 id 已被删 → 同上,WHERE 谓词自然命中 0 行

### 6.4 删除 / 写入并发

- cursor 翻页中数据被删:下一页 WHERE `id < deleted_id` 仍然走通,只是少一行,**接受**
- cursor 翻页中插入新数据:新数据 id > token,不会在已翻过页里出现,**接受**(这正是 cursor 比 offset 强的地方)

---

## 7. 测试 / 守护

- 单测:`CursorCodecTest`(encode/decode/损坏 token)、`PageQueryTest`(of 工厂方法)
- 集成测试:`CursorPaginationConsistencyTest` × 10 个端点
- 安全测试:`CursorTenantIsolationTest`(伪造跨租户 token,期望返回空)
- FE E2E:每个 cursor 化的 list 页一条 cursor 翻页用例

---

## 8. 回滚

如果 pilot 后发现问题:
- BE:cursor 端点临时禁用(Controller 拒绝 `?cursor=` 参数,降级到 offset),代码留着排查
- FE:`paginationMode` 全切回 'page',`usePagination` 默认值改回 offset
- 数据无影响(cursor 是无状态 token)

不需要数据库回滚。

---

## 附录 A:为什么不用 PageHelper

(略,见此前讨论:ThreadLocal 黑魔法、count 自动改写错、不解决 keyset)

## 附录 B:为什么不用 MyBatis-Plus

ADR-001 明确「全模块统一 MyBatis + JdbcTemplate」,引 MP = 换 ORM,违反硬约束。

## 附录 C:参考实现

- Stripe API cursor 设计:[Pagination | Stripe Docs](https://stripe.com/docs/api/pagination)
- GitHub Relay GraphQL cursor:[GraphQL Cursor Connections Specification](https://relay.dev/graphql/connections.htm)
- PG row-comparison for keyset:[Use The Index, Luke - Pagination](https://use-the-index-luke.com/no-offset)
