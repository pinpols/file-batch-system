SELECT count(*) FROM batch.outbox_event WHERE publish_status IN ('NEW', 'FAILED');
