#!/bin/bash

until pg_isready -h localhost -p 5432 -U $POSTGRES_USER; do
    echo "Waiting for PostgreSQL to be ready..."
    sleep 2
done

echo "PostgreSQL is ready, initializing databases..."

# Main database
PGPASSWORD=$POSTGRES_PASSWORD psql -h localhost -U $POSTGRES_USER -d postgres -f /app/db/init/schema.sql

# Legacy database
PGPASSWORD=$POSTGRES_PASSWORD_LEGACY psql -h localhost -U $POSTGRES_USER_LEGACY -d postgres -f /app/db/init/legacy_schema.sql

# Incremental migrations (idempotent — safe to run on every startup)
for migration in /app/db/init/migrations/*.sql; do
    echo "Applying migration: $migration"
    PGPASSWORD=$POSTGRES_PASSWORD psql -h localhost -U $POSTGRES_USER -d promueva -f "$migration"
done

echo "Database initialization complete"