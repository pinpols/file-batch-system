SELECT day_status, count(*), count(settled_at) AS settled
FROM batch.batch_day_instance
WHERE biz_date = :'biz_date'::date
GROUP BY day_status
ORDER BY 1;
