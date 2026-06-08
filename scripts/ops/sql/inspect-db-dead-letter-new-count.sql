SELECT COUNT(*)
FROM :"schema".dead_letter_task
WHERE replay_status = 'NEW';
