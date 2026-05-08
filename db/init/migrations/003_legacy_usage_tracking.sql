-- Migration 003: legacy endpoint usage tracking
-- Safe to re-run (all statements are idempotent)

CREATE TABLE IF NOT EXISTS public.legacy_endpoint_usage (
    id         BIGSERIAL PRIMARY KEY,
    endpoint   VARCHAR(64) NOT NULL,
    user_id    BIGINT REFERENCES public.users(id),
    user_agent TEXT,
    ip         INET,
    called_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS legacy_endpoint_usage_endpoint_called_at_idx
    ON public.legacy_endpoint_usage(endpoint, called_at);
