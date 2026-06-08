SELECT biz_date, day_status, count(*) AS n, count(settled_at) AS settled
FROM batch.batch_day_instance
WHERE biz_date BETWEEN :'start_date'::date AND :'end_date'::date
GROUP BY biz_date, day_status
ORDER BY biz_date, day_status;
