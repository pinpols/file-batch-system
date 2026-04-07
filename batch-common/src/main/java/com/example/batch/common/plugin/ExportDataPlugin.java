package com.example.batch.common.plugin;

import java.util.List;
import java.util.Map;

/**
 * 导出 GENERATE 插件：加载批次头信息和明细分页数据用于文件生成。
 */
public interface ExportDataPlugin {

    record DetailPage(List<Map<String, Object>> rows, Object nextCursor) {
        public static DetailPage empty() {
            return new DetailPage(List.of(), null);
        }
    }

    record DelimitedColumn(String header, String source) {
    }

    String id();

    /**
     * 加载批次行数据（如结算批次），返回空 map 表示未找到。
     */
    Map<String, Object> loadBatch(ExportDataContext context) throws Exception;

    /**
     * 按插件定义的游标语义加载一页明细数据，{@code nextCursor == null} 表示无更多数据。
     */
    DetailPage loadDetailPage(ExportDataContext context, Long batchId, int pageSize, Object cursor) throws Exception;

    /**
     * 可选的插件自定义分隔符列布局，模板级配置仍可覆盖此定义。
     */
    default List<DelimitedColumn> describeDelimitedColumns(ExportDataContext context, Map<String, Object> batch) {
        return List.of();
    }

    /**
     * 可选的插件自定义定长列布局，默认回退至分隔符布局。
     */
    default List<DelimitedColumn> describeFixedWidthColumns(ExportDataContext context, Map<String, Object> batch) {
        return describeDelimitedColumns(context, batch);
    }

    /**
     * 由 {@code RegisterStep} 在文件记录成功持久化后回调，插件可借此标记自身业务数据已导出（如更新状态列），默认为空实现。
     */
    default void onRegistered(ExportDataContext context, Long batchId, int exportVersion, String traceId) {
    }
}
