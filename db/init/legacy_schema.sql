--
-- Idempotent PostgreSQL legacy database initialization script
-- Safe to run multiple times - will only create if not exists
--

-- Database configuration
SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

-- Check if database exists and create if not (cannot use DO block for CREATE DATABASE)
SELECT 'CREATE DATABASE promueva_legacy'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'promueva_legacy')\gexec

-- Connect to the promueva_legacy database for the rest of the script
\connect promueva_legacy

--
-- Create custom types only if they don't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'belief_update_method') THEN
            CREATE TYPE public.belief_update_method AS ENUM (
                'DeGroot'
                );
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'cognitive_bias') THEN
            CREATE TYPE public.cognitive_bias AS ENUM (
                'DeGroot',
                'Confirmation',
                'Backfire',
                'Authority',
                'Insular'
                );
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'initial_distribution') THEN
            CREATE TYPE public.initial_distribution AS ENUM (
                'uniform',
                'bimodal',
                'normal',
                'exponential',
                'custom'
                );
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'silence_effect') THEN
            CREATE TYPE public.silence_effect AS ENUM (
                'DeGroot',
                'Memory',
                'Memoryless',
                'Recency',
                'Peers-Memory',
                'Peers-Memoryless',
                'Peers-Recency'
                );
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'silence_strategy') THEN
            CREATE TYPE public.silence_strategy AS ENUM (
                'DeGroot',
                'Majority',
                'Confidence',
                'Threshold',
                'Threshold-Influence'
                );
        END IF;
    END
$$;

-- Set defaults for table creation
SET default_tablespace = '';
SET default_table_access_method = heap;

--
-- Create sequence only if it doesn't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'runs_id_seq') THEN
            CREATE SEQUENCE public.runs_id_seq
                AS bigint
                START WITH 1
                INCREMENT BY 1
                NO MINVALUE
                NO MAXVALUE
                CACHE 1;
        END IF;
    END
$$;

--
-- Create tables only if they don't exist
--

CREATE TABLE IF NOT EXISTS public.runs
(
    id                   bigint                                                NOT NULL DEFAULT nextval('public.runs_id_seq'::regclass),
    run_time             bigint                      DEFAULT '-1'::integer     NOT NULL,
    build_time           bigint                      DEFAULT '-1'::integer     NOT NULL,
    run_date             timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    number_of_networks   integer                                               NOT NULL,
    iteration_limit      integer                                               NOT NULL,
    stop_threshold       real                                                  NOT NULL,
    initial_distribution public.initial_distribution                           NOT NULL,
    run_mode             smallint                                              NOT NULL,
    save_mode            smallint                                              NOT NULL
);

CREATE TABLE IF NOT EXISTS public.networks
(
    id                 uuid                          NOT NULL,
    run_time           bigint  DEFAULT '-1'::integer NOT NULL,
    build_time         bigint  DEFAULT '-1'::integer NOT NULL,
    run_id             integer                       NOT NULL,
    number_of_agents   integer                       NOT NULL,
    final_round        integer DEFAULT '-1'::integer NOT NULL,
    name               character varying(16)         NOT NULL,
    simulation_outcome boolean DEFAULT false         NOT NULL
);

CREATE TABLE IF NOT EXISTS public.agents
(
    id                   uuid                        NOT NULL,
    network_id           uuid                        NOT NULL,
    number_of_neighbors  integer                     NOT NULL,
    tolerance_radius     real                        NOT NULL,
    tol_offset           real                        NOT NULL,
    silence_strategy     public.silence_strategy     NOT NULL,
    silence_effect       public.silence_effect       NOT NULL,
    belief_update_method public.belief_update_method NOT NULL,
    expression_threshold real,
    open_mindedness      integer,
    name                 character varying(32) DEFAULT NULL::character varying
);

CREATE TABLE IF NOT EXISTS public.agent_states_silent
(
    agent_id   uuid    NOT NULL,
    round      integer NOT NULL,
    belief     real    NOT NULL,
    state_data bytea
);

CREATE TABLE IF NOT EXISTS public.agent_states_speaking
(
    agent_id   uuid    NOT NULL,
    round      integer NOT NULL,
    belief     real    NOT NULL,
    state_data bytea
);

CREATE TABLE IF NOT EXISTS public.generated_run_parameters
(
    run_id              integer NOT NULL,
    degree_distribution real    NOT NULL,
    density             integer NOT NULL
);

CREATE TABLE IF NOT EXISTS public.neighbors
(
    source         uuid                  NOT NULL,
    target         uuid                  NOT NULL,
    value          real                  NOT NULL,
    cognitive_bias public.cognitive_bias NOT NULL
);

CREATE TABLE IF NOT EXISTS public.optional_variables
(
    id     bit(8)                 NOT NULL,
    type   bit(8)                 NOT NULL,
    length smallint               NOT NULL,
    name   character varying(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.users
(
    id         uuid                   NOT NULL,
    email      character varying(256) NOT NULL,
    password   character varying(500) NOT NULL,
    lastname   character varying(256),
    name       character varying(256),
    created_at timestamp with time zone,
    birthday   date,
    job        character varying(256),
    gender     character varying,
    role       integer DEFAULT 4      NOT NULL
);

--
-- Set sequence ownership only if not already set
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1
                       FROM pg_depend d
                                JOIN pg_class c ON d.objid = c.oid
                                JOIN pg_class t ON d.refobjid = t.oid
                       WHERE c.relname = 'runs_id_seq'
                         AND t.relname = 'runs'
                         AND d.deptype = 'a') THEN
            ALTER SEQUENCE public.runs_id_seq OWNED BY public.runs.id;
        END IF;
    END
$$;

--
-- Add table comments (safe to run multiple times)
--

COMMENT ON TABLE public.runs IS 'Stores simulation run metadata and configuration parameters';
COMMENT ON COLUMN public.runs.id IS 'Primary key - unique identifier for each simulation run';
COMMENT ON COLUMN public.runs.run_time IS 'Total execution time in nanoseconds';
COMMENT ON COLUMN public.runs.build_time IS 'Network construction time in nanoseconds';
COMMENT ON COLUMN public.runs.run_date IS 'Timestamp when the simulation was initiated';
COMMENT ON COLUMN public.runs.number_of_networks IS 'Number of networks simulated in this run';
COMMENT ON COLUMN public.runs.iteration_limit IS 'Maximum number of allowed iterations';
COMMENT ON COLUMN public.runs.stop_threshold IS 'Convergence threshold for opinion stability';
COMMENT ON COLUMN public.runs.initial_distribution IS 'Distribution type for initial agent beliefs';
COMMENT ON COLUMN public.runs.run_mode IS 'Type of simulation run';
COMMENT ON COLUMN public.runs.save_mode IS 'Data persistence configuration type';

--
-- Add constraints only if they don't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'runs_pkey') THEN
            ALTER TABLE public.runs
                ADD CONSTRAINT runs_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'networks_pkey') THEN
            ALTER TABLE public.networks
                ADD CONSTRAINT networks_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'agents_pkey') THEN
            ALTER TABLE public.agents
                ADD CONSTRAINT agents_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'agent_states_silent_pkey') THEN
            ALTER TABLE public.agent_states_silent
                ADD CONSTRAINT agent_states_silent_pkey PRIMARY KEY (agent_id, round);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'agent_states_speaking_pkey') THEN
            ALTER TABLE public.agent_states_speaking
                ADD CONSTRAINT agent_states_speaking_pkey PRIMARY KEY (agent_id, round);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'generated_run_parameters_pkey') THEN
            ALTER TABLE public.generated_run_parameters
                ADD CONSTRAINT generated_run_parameters_pkey PRIMARY KEY (run_id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'optional_variables_pkey') THEN
            ALTER TABLE public.optional_variables
                ADD CONSTRAINT optional_variables_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_pkey') THEN
            ALTER TABLE public.users
                ADD CONSTRAINT users_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

--
-- Create indexes only if they don't exist
--

CREATE INDEX IF NOT EXISTS idx_agents_network_id ON public.agents USING btree (network_id);
CREATE INDEX IF NOT EXISTS idx_neighbors_source ON public.neighbors USING btree (source);
CREATE INDEX IF NOT EXISTS idx_runs_run_date ON public.runs USING btree (run_date);

--
-- Add foreign key constraints only if they don't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'networks_run_id_fkey') THEN
            ALTER TABLE public.networks
                ADD CONSTRAINT networks_run_id_fkey
                    FOREIGN KEY (run_id) REFERENCES public.runs (id) ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'agents_network_id_fkey') THEN
            ALTER TABLE public.agents
                ADD CONSTRAINT agents_network_id_fkey
                    FOREIGN KEY (network_id) REFERENCES public.networks (id) ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'agent_states_silent_agent_id_fkey') THEN
            ALTER TABLE public.agent_states_silent
                ADD CONSTRAINT agent_states_silent_agent_id_fkey
                    FOREIGN KEY (agent_id) REFERENCES public.agents (id) ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'agent_states_speaking_agent_id_fkey') THEN
            ALTER TABLE public.agent_states_speaking
                ADD CONSTRAINT agent_states_speaking_agent_id_fkey
                    FOREIGN KEY (agent_id) REFERENCES public.agents (id) ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'run_id_fk') THEN
            ALTER TABLE public.generated_run_parameters
                ADD CONSTRAINT run_id_fk
                    FOREIGN KEY (run_id) REFERENCES public.runs (id) ON DELETE CASCADE;
        END IF;
    END
$$;

-- Set table ownership (safe to run multiple times)
ALTER TABLE public.runs
    OWNER TO postgres;
ALTER TABLE public.networks
    OWNER TO postgres;
ALTER TABLE public.agents
    OWNER TO postgres;
ALTER TABLE public.agent_states_silent
    OWNER TO postgres;
ALTER TABLE public.agent_states_speaking
    OWNER TO postgres;
ALTER TABLE public.generated_run_parameters
    OWNER TO postgres;
ALTER TABLE public.neighbors
    OWNER TO postgres;
ALTER TABLE public.optional_variables
    OWNER TO postgres;
ALTER TABLE public.users
    OWNER TO postgres;
ALTER SEQUENCE public.runs_id_seq OWNER TO postgres;
ALTER TYPE public.belief_update_method OWNER TO postgres;
ALTER TYPE public.cognitive_bias OWNER TO postgres;
ALTER TYPE public.initial_distribution OWNER TO postgres;
ALTER TYPE public.silence_effect OWNER TO postgres;
ALTER TYPE public.silence_strategy OWNER TO postgres;