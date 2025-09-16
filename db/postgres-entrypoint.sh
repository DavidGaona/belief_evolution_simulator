#!/bin/bash
set -e

docker-entrypoint.sh postgres &
PG_PID=$!

until pg_isready -h localhost -p 5432 -U ${POSTGRES_USER:-postgres}; do
    echo "Waiting for PostgreSQL to start..."
    sleep 2
done

echo "PostgreSQL started, running initialization..."

/app/init_db.sh

echo "Initialization complete, PostgreSQL running in foreground"

wait $PG_PID