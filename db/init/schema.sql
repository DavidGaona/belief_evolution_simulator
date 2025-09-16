--
-- Idempotent PostgreSQL database initialization script
-- Safe to run multiple times - will only create if not exists
--

-- Database configuration (will only apply if database is being created)
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

--
-- Create database only if it doesn't exist
-- Note: We need to conditionally create the database 'promueva'
--

-- Check if database exists and create if not (cannot use DO block for CREATE DATABASE)
SELECT 'CREATE DATABASE promueva WITH TEMPLATE = template0 ENCODING = ''UTF8'' LOCALE_PROVIDER = ''libc'' LOCALE = ''English_United States.1252'''
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'promueva')\gexec

-- Connect to the promueva database
\connect promueva

--
-- Create custom types only if they don't exist
--

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
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'role') THEN
            CREATE TYPE public.role AS ENUM (
                'admin',
                'user',
                'guest'
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
                'Memoryless'
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
                'Threshold'
                );
        END IF;
    END
$$;

-- Set defaults for table creation
SET default_tablespace = '';
SET default_table_access_method = heap;

--
-- Create tables only if they don't exist
--

CREATE TABLE IF NOT EXISTS public.generated_runs
(
    id                 bigint  NOT NULL,
    seed               bigint  NOT NULL,
    density            integer NOT NULL,
    iteration_limit    integer NOT NULL,
    total_networks     integer NOT NULL,
    agents_per_network integer NOT NULL,
    stop_threshold     real    NOT NULL
);

CREATE TABLE IF NOT EXISTS public.custom_runs
(
    id              bigint                NOT NULL,
    iteration_limit integer               NOT NULL,
    stop_threshold  real                  NOT NULL,
    run_name        character varying(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.agent_type_distributions
(
    run_id           bigint                  NOT NULL,
    silence_strategy public.silence_strategy NOT NULL,
    silence_effect   public.silence_effect   NOT NULL,
    count            integer                 NOT NULL
);

CREATE TABLE IF NOT EXISTS public.cognitive_bias_distributions
(
    run_id         bigint                NOT NULL,
    cognitive_bias public.cognitive_bias NOT NULL,
    count          integer               NOT NULL
);

CREATE TABLE IF NOT EXISTS public.custom_agents
(
    run_id             bigint                  NOT NULL,
    silence_strategy   public.silence_strategy NOT NULL,
    silence_effect     public.silence_effect   NOT NULL,
    belief             real                    NOT NULL,
    tolerance_radius   real                    NOT NULL,
    tolerance_offset   real                    NOT NULL,
    name               character varying(32)   NOT NULL,
    majority_threshold real,
    confidence         real
);

CREATE TABLE IF NOT EXISTS public.custom_neighbors
(
    run_id         bigint                NOT NULL,
    influence      real                  NOT NULL,
    cognitive_bias public.cognitive_bias NOT NULL,
    source         character varying(32) NOT NULL,
    target         character varying(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.network_results
(
    run_id            bigint  NOT NULL,
    build_time        bigint  NOT NULL,
    run_time          bigint  NOT NULL,
    network_number    integer NOT NULL,
    final_round       integer NOT NULL,
    reached_consensus boolean NOT NULL
);

--
-- Create sequence only if it doesn't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'user_id_seq') THEN
            CREATE SEQUENCE public.user_id_seq
                AS integer
                START WITH 1
                INCREMENT BY 1
                NO MINVALUE
                NO MAXVALUE
                CACHE 1;
        END IF;
    END
$$;

CREATE TABLE IF NOT EXISTS public.users
(
    id           integer                  NOT NULL DEFAULT nextval('public.user_id_seq'::regclass),
    firebase_uid uuid                     NOT NULL,
    created_at   timestamp with time zone NOT NULL,
    updated_at   timestamp with time zone NOT NULL,
    role         public.role              NOT NULL,
    email        character varying(256)   NOT NULL,
    name         character varying(32)    NOT NULL,
    is_active    boolean                  NOT NULL
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
                       WHERE c.relname = 'user_id_seq'
                         AND t.relname = 'users'
                         AND d.deptype = 'a') THEN
            ALTER SEQUENCE public.user_id_seq OWNED BY public.users.id;
        END IF;
    END
$$;

--
-- Add constraints only if they don't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'a_pkey') THEN
            ALTER TABLE public.generated_runs
                ADD CONSTRAINT a_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custom_runs_pkey') THEN
            ALTER TABLE public.custom_runs
                ADD CONSTRAINT custom_runs_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'generated_run_agent_type_distribution_pkey') THEN
            ALTER TABLE public.agent_type_distributions
                ADD CONSTRAINT generated_run_agent_type_distribution_pkey PRIMARY KEY (run_id);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Cognitive_bias_distribution_pkey') THEN
            ALTER TABLE public.cognitive_bias_distributions
                ADD CONSTRAINT "Cognitive_bias_distribution_pkey" PRIMARY KEY (run_id, cognitive_bias);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custom_agent_pkey') THEN
            ALTER TABLE public.custom_agents
                ADD CONSTRAINT custom_agent_pkey PRIMARY KEY (run_id, name);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custom_neighbor_pkey') THEN
            ALTER TABLE public.custom_neighbors
                ADD CONSTRAINT custom_neighbor_pkey PRIMARY KEY (run_id, source, target);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'network_results_pkey') THEN
            ALTER TABLE public.network_results
                ADD CONSTRAINT network_results_pkey PRIMARY KEY (run_id, network_number);
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_pkey') THEN
            ALTER TABLE public.users
                ADD CONSTRAINT user_pkey PRIMARY KEY (id);
        END IF;
    END
$$;

--
-- Add foreign key constraints only if they don't exist
--

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'generated_run_pk') THEN
            ALTER TABLE public.agent_type_distributions
                ADD CONSTRAINT generated_run_pk FOREIGN KEY (run_id) REFERENCES public.generated_runs (id) ON UPDATE CASCADE ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'generated_run_cognitive_bias_fk') THEN
            ALTER TABLE public.cognitive_bias_distributions
                ADD CONSTRAINT generated_run_cognitive_bias_fk FOREIGN KEY (run_id) REFERENCES public.generated_runs (id) ON UPDATE CASCADE ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custom_agent_run_id_fkey') THEN
            ALTER TABLE public.custom_agents
                ADD CONSTRAINT custom_agent_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.custom_runs (id) ON UPDATE CASCADE ON DELETE CASCADE;
        END IF;
    END
$$;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custom_neighbor_run_id_fkey') THEN
            ALTER TABLE public.custom_neighbors
                ADD CONSTRAINT custom_neighbor_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.custom_runs (id) ON UPDATE CASCADE ON DELETE CASCADE;
        END IF;
    END
$$;

-- Set table ownership (safe to run multiple times)
ALTER TABLE public.agent_type_distributions
    OWNER TO postgres;
ALTER TABLE public.cognitive_bias_distributions
    OWNER TO postgres;
ALTER TABLE public.custom_agents
    OWNER TO postgres;
ALTER TABLE public.custom_neighbors
    OWNER TO postgres;
ALTER TABLE public.custom_runs
    OWNER TO postgres;
ALTER TABLE public.generated_runs
    OWNER TO postgres;
ALTER TABLE public.network_results
    OWNER TO postgres;
ALTER TABLE public.users
    OWNER TO postgres;
ALTER SEQUENCE public.user_id_seq OWNER TO postgres;
ALTER TYPE public.cognitive_bias OWNER TO postgres;
ALTER TYPE public.role OWNER TO postgres;
ALTER TYPE public.silence_effect OWNER TO postgres;
ALTER TYPE public.silence_strategy OWNER TO postgres;