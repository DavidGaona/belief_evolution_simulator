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

echo "Database initialization complete"