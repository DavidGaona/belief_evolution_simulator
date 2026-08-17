-- Migration 006: persisted WS frames per round for timeline scrubber,
-- race-condition fix on small completed sims, and refresh-resilient streams.
--
-- Stores the binary WS frame as emitted by AgentProcessor.sendRoundToWebSocketServer,
-- sliced per AgentProcessor (one row per (network_id, round, starts_at)).
-- Read endpoint concatenates slices ordered by (round, starts_at) to reconstruct
-- the on-the-wire format byte-for-byte.
--
-- Retention is governed by frame_retention on the run row:
--   'ephemeral'  -> deleted ~1h after the run leaves 'running' (cron in Server).
--   'persistent' -> kept until DELETE /simulations/{runId} (which deletes by run_id).
-- 'persistent' is admin-only (enforced in the REST handler).
--
-- No FK to public.networks: that table is only populated when saveMode.includesNetworks
-- is set, so frames from DEBUG-mode runs would be orphaned. Cleanup is by run_id via
-- cleanupEphemeralFrames (cron) and explicit deletion on DELETE /simulations/{runId}.
--
-- Safe to re-run.

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'frame_retention') THEN
            CREATE TYPE public.frame_retention AS ENUM ('ephemeral', 'persistent');
        END IF;
    END
$$;

ALTER TABLE public.generated_runs
    ADD COLUMN IF NOT EXISTS frame_retention public.frame_retention NOT NULL DEFAULT 'ephemeral';
ALTER TABLE public.generated_runs
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

ALTER TABLE public.custom_runs
    ADD COLUMN IF NOT EXISTS frame_retention public.frame_retention NOT NULL DEFAULT 'ephemeral';
ALTER TABLE public.custom_runs
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS public.network_frames
(
    network_id UUID    NOT NULL,
    round      INTEGER NOT NULL,
    starts_at  INTEGER NOT NULL,
    frame      BYTEA   NOT NULL,
    run_id     BIGINT  NOT NULL
);

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'network_frames_pkey') THEN
            ALTER TABLE public.network_frames
                ADD CONSTRAINT network_frames_pkey PRIMARY KEY (network_id, round, starts_at);
        END IF;
    END
$$;

CREATE INDEX IF NOT EXISTS network_frames_run_idx ON public.network_frames(run_id);

ALTER TABLE public.network_frames OWNER TO postgres;
