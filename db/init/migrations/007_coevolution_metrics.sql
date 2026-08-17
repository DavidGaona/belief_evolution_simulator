-- Migration 006: per-round structural metrics for co-evolutionary simulations
-- Stores assortativity r^t, fragmentation index Φ_t, and SCC count κ(G_t) computed
-- after each topology-evolution step in Network.scala.
-- Safe to re-run (all statements are idempotent).

\connect promueva_legacy

CREATE TABLE IF NOT EXISTS public.network_coevolution_metrics
(
    network_id    uuid    NOT NULL,
    round_number  integer NOT NULL,
    assortativity real,           -- NULL when beliefs are uniform (NaN case)
    fragmentation real    NOT NULL,
    scc_count     integer NOT NULL,
    CONSTRAINT network_coevolution_metrics_pkey PRIMARY KEY (network_id, round_number)
);

CREATE INDEX IF NOT EXISTS network_coevolution_metrics_network_id_idx
    ON public.network_coevolution_metrics (network_id);

COMMENT ON TABLE public.network_coevolution_metrics
    IS 'Per-round structural convergence metrics for co-evolutionary networks';
COMMENT ON COLUMN public.network_coevolution_metrics.assortativity
    IS 'Weighted Pearson opinion assortativity r^t ∈ [-1,1]; NULL when beliefs are uniform';
COMMENT ON COLUMN public.network_coevolution_metrics.fragmentation
    IS 'Normalised fragmentation index Φ_t = (κ−1)/(N−1) ∈ [0,1]';
COMMENT ON COLUMN public.network_coevolution_metrics.scc_count
    IS 'Number of strongly connected components κ(G_t)';

DO
$$
    BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'network_coevolution_metrics_network_id_fkey'
        ) THEN
            ALTER TABLE public.network_coevolution_metrics
                ADD CONSTRAINT network_coevolution_metrics_network_id_fkey
                FOREIGN KEY (network_id) REFERENCES public.networks (id);
        END IF;
    END
$$;
