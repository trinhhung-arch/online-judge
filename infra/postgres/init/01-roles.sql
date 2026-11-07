DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_migrator') THEN
        CREATE ROLE oj_migrator LOGIN PASSWORD 'ojmigrator';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oj_app') THEN
        CREATE ROLE oj_app LOGIN PASSWORD 'ojapp';
    END IF;
END
$$;

COMMENT ON ROLE oj_migrator IS 'Flyway. Sở hữu schema, có DDL.';
COMMENT ON ROLE oj_app      IS 'oj-api lúc chạy. DML, không DDL. Xem V8.';
