SELECT COUNT(*)
FROM :"schema".alert_event
WHERE severity = 'CRITICAL'
  AND last_seen_at >= NOW() - (:alert_lookback_minutes::bigint * INTERVAL '1 minute');
