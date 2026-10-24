WITH manifest_objects AS (
    SELECT
        'SCHEMA'::text AS object_type,
        n.nspname AS object_identity,
        jsonb_build_array(
            obj_description(n.oid, 'pg_namespace'),
            n.nspacl::text
        ) AS object_definition
    FROM pg_namespace n
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')

    UNION ALL

    SELECT
        'RELATION',
        format('%I.%I', n.nspname, c.relname),
        jsonb_build_array(
            c.relkind::text,
            c.relpersistence::text,
            c.relispartition,
            c.relrowsecurity,
            c.relforcerowsecurity,
            c.reloptions,
            c.relacl::text,
            am.amname,
            CASE WHEN c.relkind = 'p' THEN pg_get_partkeydef(c.oid) END,
            CASE WHEN c.relkind IN ('v', 'm') THEN pg_get_viewdef(c.oid, true) END
        )
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_am am ON am.oid = c.relam
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')
      AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')

    UNION ALL

    SELECT
        'COLUMN',
        format('%I.%I.%I', n.nspname, c.relname, a.attname),
        jsonb_build_array(
            format_type(a.atttypid, a.atttypmod),
            a.attnotnull,
            a.attidentity::text,
            a.attgenerated::text,
            pg_get_expr(d.adbin, d.adrelid),
            coll.collname,
            a.attstorage::text,
            a.attcompression::text,
            col_description(c.oid, a.attnum)
        )
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
    LEFT JOIN pg_collation coll ON coll.oid = a.attcollation
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND a.attnum > 0
      AND NOT a.attisdropped

    UNION ALL

    SELECT
        'CONSTRAINT',
        format('%I.%I.%I', n.nspname, c.relname, con.conname),
        jsonb_build_array(
            con.contype::text,
            pg_get_constraintdef(con.oid, true),
            con.convalidated,
            con.condeferrable,
            con.condeferred,
            con.conislocal,
            con.coninhcount,
            con.connoinherit,
            con.confmatchtype::text,
            con.confupdtype::text,
            con.confdeltype::text
        )
    FROM pg_constraint con
    JOIN pg_class c ON c.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')

    UNION ALL

    SELECT
        'INDEX',
        format('%I.%I.%I', n.nspname, t.relname, i.relname),
        jsonb_build_array(
            pg_get_indexdef(i.oid),
            ix.indisunique,
            ix.indisprimary,
            ix.indisexclusion,
            ix.indisvalid,
            ix.indisready,
            ix.indislive,
            ix.indisclustered,
            ix.indisreplident,
            ix.indnullsnotdistinct
        )
    FROM pg_index ix
    JOIN pg_class i ON i.oid = ix.indexrelid
    JOIN pg_class t ON t.oid = ix.indrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')

    UNION ALL

    SELECT
        'TRIGGER',
        format('%I.%I.%I', n.nspname, c.relname, t.tgname),
        jsonb_build_array(
            pg_get_triggerdef(t.oid, true),
            t.tgenabled::text,
            t.tgisinternal,
            t.tgconstraint
        )
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')
      AND NOT t.tgisinternal

    UNION ALL

    SELECT
        'POLICY',
        format('%I.%I.%I', n.nspname, c.relname, p.polname),
        jsonb_build_array(
            p.polcmd::text,
            p.polpermissive,
            ARRAY(
                SELECT CASE WHEN role_oid = 0 THEN 'PUBLIC' ELSE r.rolname END
                FROM unnest(p.polroles) AS role_oid
                LEFT JOIN pg_roles r ON r.oid = role_oid
                ORDER BY 1
            ),
            pg_get_expr(p.polqual, p.polrelid),
            pg_get_expr(p.polwithcheck, p.polrelid)
        )
    FROM pg_policy p
    JOIN pg_class c ON c.oid = p.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')

    UNION ALL

    SELECT
        'ROUTINE',
        format('%I.%I(%s)', n.nspname, p.proname,
               pg_get_function_identity_arguments(p.oid)),
        jsonb_build_array(
            p.prokind::text,
            p.prorettype::regtype::text,
            p.prosecdef,
            p.provolatile::text,
            p.proparallel::text,
            p.proleakproof,
            p.proisstrict,
            p.proconfig,
            p.proacl::text,
            pg_get_functiondef(p.oid)
        )
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')
      AND p.prokind IN ('f', 'p')

    UNION ALL

    SELECT
        'TYPE',
        format('%I.%I', n.nspname, t.typname),
        jsonb_build_array(
            t.typtype::text,
            CASE WHEN t.typbasetype <> 0
                 THEN format_type(t.typbasetype, t.typtypmod) END,
            t.typnotnull,
            t.typdefault,
            CASE WHEN t.typtype = 'e' THEN (
                SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder)
                FROM pg_enum e
                WHERE e.enumtypid = t.oid
            ) END,
            CASE WHEN t.typtype = 'r' THEN (
                SELECT jsonb_build_array(
                    rng.rngsubtype::regtype::text,
                    rng.rngcollation::regcollation::text,
                    rng.rngcanonical::regprocedure::text,
                    rng.rngsubdiff::regprocedure::text
                )
                FROM pg_range rng
                WHERE rng.rngtypid = t.oid
            ) END,
            t.typacl::text
        )
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')
      AND (t.typtype IN ('e', 'd', 'r') OR (t.typtype = 'c' AND t.typrelid = 0))

    UNION ALL

    SELECT
        'SEQUENCE',
        format('%I.%I', n.nspname, c.relname),
        jsonb_build_array(
            s.seqtypid::regtype::text,
            s.seqstart,
            s.seqincrement,
            s.seqmin,
            s.seqmax,
            s.seqcache,
            s.seqcycle,
            dep.refobjid::regclass::text,
            dep.refobjsubid
        )
    FROM pg_sequence s
    JOIN pg_class c ON c.oid = s.seqrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN LATERAL (
        SELECT d.refobjid, d.refobjsubid
        FROM pg_depend d
        WHERE d.classid = 'pg_class'::regclass
          AND d.objid = c.oid
          AND d.refclassid = 'pg_class'::regclass
          AND d.deptype IN ('a', 'i')
        ORDER BY d.deptype
        LIMIT 1
    ) dep ON true
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz')

    UNION ALL

    SELECT
        'PARTITION',
        format('%I.%I -> %I.%I', parent_ns.nspname, parent.relname,
               child_ns.nspname, child.relname),
        jsonb_build_array(
            pg_get_expr(child.relpartbound, child.oid, true)
        )
    FROM pg_inherits inh
    JOIN pg_class parent ON parent.oid = inh.inhparent
    JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
    JOIN pg_class child ON child.oid = inh.inhrelid
    JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
    WHERE parent_ns.nspname IN ('batch', 'archive', 'biz', 'quartz')
      AND parent.relkind = 'p'

    UNION ALL

    SELECT
        'DEFAULT_PRIVILEGE',
        format('%I.%s', COALESCE(n.nspname, '<global>'), d.defaclobjtype),
        jsonb_build_array(d.defaclacl::text)
    FROM pg_default_acl d
    LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz', 'quartz') OR d.defaclnamespace = 0

    UNION ALL

    SELECT
        'EXTENSION',
        e.extname,
        jsonb_build_array(e.extversion, n.nspname)
    FROM pg_extension e
    JOIN pg_namespace n ON n.oid = e.extnamespace
)
SELECT
    object_type,
    jsonb_build_array(object_type, object_identity, object_definition)::text AS canonical_object
FROM manifest_objects
ORDER BY object_type, object_identity, object_definition;
