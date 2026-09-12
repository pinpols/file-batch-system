-- 容量轮次必须从空统计开始，避免历史 SQL 调用污染本轮热点排序。
SELECT pg_stat_statements_reset();
