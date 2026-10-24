select jsonb_build_object(
  'postgres', version(),
  'database', current_database(),
  'role', current_user,
  'roleSuperuser', (select rolsuper from pg_roles where rolname=current_user),
  'roleBypassRls', (select rolbypassrls from pg_roles where rolname=current_user),
  'jobInstanceRls', (select jsonb_build_object('enabled',relrowsecurity,'forced',relforcerowsecurity)
                    from pg_class where oid='batch.job_instance'::regclass),
  'settings', (select jsonb_object_agg(name,setting) from pg_settings
               where name in ('shared_buffers','work_mem','effective_cache_size','max_connections',
                              'jit','random_page_cost','seq_page_cost')),
  'migrationHistory', (select jsonb_agg(jsonb_build_object(
                         'rank',installed_rank,'version',version,'script',script,
                         'checksum',checksum,'success',success) order by installed_rank)
                       from batch.flyway_schema_history),
  'parentIndexes', (select jsonb_agg(indexdef order by tablename,indexname)
                    from pg_indexes where schemaname='batch'
                    and tablename like 'job_instance%' and indexdef ilike '%parent_instance_id%')
)::text
