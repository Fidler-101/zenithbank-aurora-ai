#!/bin/bash
# ============================================================
# Creates one database per service inside the single Postgres
# container used for local/dev docker-compose. Runs automatically
# on first container start (official postgres image convention:
# anything in /docker-entrypoint-initdb.d/ runs once, against an
# empty data directory).
#
# Matches the database-per-service split already in your
# application.properties files:
#   customer-service -> zenithbank_customer
#   banking-service   -> zenithbank_banking
#   payment-service   -> zenithbank_payment
# ============================================================
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE zenithbank_customer OWNER $POSTGRES_USER;
    CREATE DATABASE zenithbank_banking  OWNER $POSTGRES_USER;
    CREATE DATABASE zenithbank_payment  OWNER $POSTGRES_USER;
EOSQL

echo "Created zenithbank_customer, zenithbank_banking, zenithbank_payment"
