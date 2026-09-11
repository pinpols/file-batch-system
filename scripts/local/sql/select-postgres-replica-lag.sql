SELECT coalesce(
    extract(epoch FROM CURRENT_TIMESTAMP - pg_last_xact_replay_timestamp())::integer,
    0
);
