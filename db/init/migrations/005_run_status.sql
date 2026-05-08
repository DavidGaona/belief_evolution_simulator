-- Migration 005: add run lifecycle status to generated_runs and custom_runs.
-- Backfills pre-existing rows to 'completed' (assumed terminated), then sets
-- the column default to 'running' for new inserts.
-- Safe to re-run (idempotent).

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'run_status') THEN
            CREATE TYPE public.run_status AS ENUM ('running', 'completed', 'cancelled', 'error');
        END IF;
    END
$$;

ALTER TABLE public.generated_runs
    ADD COLUMN IF NOT EXISTS status public.run_status NOT NULL DEFAULT 'completed';
ALTER TABLE public.generated_runs
    ALTER COLUMN status SET DEFAULT 'running';

ALTER TABLE public.custom_runs
    ADD COLUMN IF NOT EXISTS status public.run_status NOT NULL DEFAULT 'completed';
ALTER TABLE public.custom_runs
    ALTER COLUMN status SET DEFAULT 'running';
