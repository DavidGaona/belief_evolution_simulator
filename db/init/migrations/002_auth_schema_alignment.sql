\connect promueva

-- Migration tracking table (idempotent)
CREATE TABLE IF NOT EXISTS public.schema_migrations (
    migration_id VARCHAR(64) PRIMARY KEY,
    applied_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.schema_migrations
        WHERE migration_id = '002_auth_schema_alignment'
    ) THEN

        -- 1. Fix firebase_uid: uuid → VARCHAR(128)
        --    The previous type (uuid) rejects real Firebase UIDs (28-char alphanumeric).
        ALTER TABLE public.users
            ALTER COLUMN firebase_uid TYPE VARCHAR(128) USING firebase_uid::text;

        -- Add the UNIQUE index required for ON CONFLICT (firebase_uid)
        IF NOT EXISTS (
            SELECT 1 FROM pg_indexes
            WHERE tablename = 'users' AND indexname = 'users_firebase_uid_unique'
        ) THEN
            CREATE UNIQUE INDEX users_firebase_uid_unique ON public.users(firebase_uid);
        END IF;

        -- 2. Widen name column to match frontend expectation
        ALTER TABLE public.users
            ALTER COLUMN name TYPE VARCHAR(255);

        -- 3. Create new user_role enum aligned with frontend UserRole type
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
            CREATE TYPE public.user_role AS ENUM (
                'Administrator',
                'Researcher',
                'BaseUser',
                'Guest'
            );
        END IF;

        -- 4. Create user_roles table (many-to-many, replaces single role column)
        CREATE TABLE IF NOT EXISTS public.user_roles (
            user_id INTEGER     NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
            role    public.user_role NOT NULL,
            PRIMARY KEY (user_id, role)
        );

        -- 5. Backfill roles from existing users.role column
        INSERT INTO public.user_roles (user_id, role)
        SELECT
            id,
            CASE role::text
                WHEN 'admin' THEN 'Administrator'::public.user_role
                WHEN 'user'  THEN 'BaseUser'::public.user_role
                ELSE              'Guest'::public.user_role
            END
        FROM public.users
        ON CONFLICT DO NOTHING;

        -- 6. Add photo column
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'public.users'::regclass
              AND attname = 'photo'
              AND NOT attisdropped
        ) THEN
            ALTER TABLE public.users ADD COLUMN photo TEXT;
        END IF;

        -- 7. Rename is_active → deactivated and invert boolean semantics
        --    is_active = true  means NOT deactivated
        --    deactivated = true means NOT active
        IF EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'public.users'::regclass
              AND attname = 'is_active'
              AND NOT attisdropped
        ) THEN
            ALTER TABLE public.users RENAME COLUMN is_active TO deactivated;
            UPDATE public.users SET deactivated = NOT deactivated;
        END IF;

        -- 8. Drop old single-role column (data already in user_roles)
        IF EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'public.users'::regclass
              AND attname = 'role'
              AND NOT attisdropped
        ) THEN
            ALTER TABLE public.users DROP COLUMN role;
        END IF;

        -- 9. Add user_id (nullable) to generated_runs
        --    Nullable for backward-compat with legacy rows; new rows always have user_id
        --    enforced at the application layer.
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'public.generated_runs'::regclass
              AND attname = 'user_id'
              AND NOT attisdropped
        ) THEN
            ALTER TABLE public.generated_runs
                ADD COLUMN user_id INTEGER REFERENCES public.users(id);
            CREATE INDEX generated_runs_user_id_idx ON public.generated_runs(user_id);
        END IF;

        -- 10. Add user_id (nullable) to custom_runs
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = 'public.custom_runs'::regclass
              AND attname = 'user_id'
              AND NOT attisdropped
        ) THEN
            ALTER TABLE public.custom_runs
                ADD COLUMN user_id INTEGER REFERENCES public.users(id);
            CREATE INDEX custom_runs_user_id_idx ON public.custom_runs(user_id);
        END IF;

        INSERT INTO public.schema_migrations (migration_id)
        VALUES ('002_auth_schema_alignment');

    END IF;
END $$;