-- V1 — baseline.
--
-- Flyway owns the schema (hard rule 5); Hibernate runs with ddl-auto: validate and never
-- generates DDL. This migration exists to prove that ownership end to end before any real
-- table depends on it: if Flyway cannot reach the database or cannot apply this file, the
-- application refuses to start rather than coming up against an empty schema.
--
-- The real master data arrives in V2__master_data.sql. This table is a placeholder and is
-- expected to stay small; it is not mapped to a JPA entity.

CREATE TABLE app_metadata (
    meta_key   TEXT PRIMARY KEY,
    meta_value TEXT NOT NULL
);

INSERT INTO app_metadata (meta_key, meta_value)
VALUES ('schema_baseline', 'V1');
