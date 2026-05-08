-- Migration 004: fix agent_type_distributions primary key
-- The PK was incorrectly defined as (run_id) only, preventing runs with
-- more than one agent type. Correct PK is (run_id, silence_strategy, silence_effect).
-- Safe to re-run (idempotent).

DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'generated_run_agent_type_distribution_pkey') THEN
            ALTER TABLE public.agent_type_distributions
                DROP CONSTRAINT generated_run_agent_type_distribution_pkey;
        END IF;

        ALTER TABLE public.agent_type_distributions
            ADD CONSTRAINT generated_run_agent_type_distribution_pkey
                PRIMARY KEY (run_id, silence_strategy, silence_effect);
    END
$$;
