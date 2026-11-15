#!/usr/bin/env bash
# ==============================================================================
# FRESH — Microtask 1D.2.1 / 1D.2.2 PostgreSQL Runtime Certification Runner
# ==============================================================================
# 1. Loads database defaults aligned with `backend/.env.example` (DB_NAME, DB_USER,
#    DB_PASSWORD, DB_HOST, DB_PORT).
# 2. Checks for a running and reachable PostgreSQL service (`pg_isready` +
#    `psycopg2` connection verification).
# 3. Runs Django system check and migration drift verification.
# 4. Applies all Django migrations (including forward/reverse verification of
#    `orders 0004 <-> 0005`) to the configured PostgreSQL database.
# 5. Executes the targeted Django ORM + PostgreSQL test classes that are skipped
#    when PostgreSQL connectivity is absent, followed by the full test suite.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${BACKEND_DIR}"

# Load defaults from backend/.env.example when environment variables are not already set
if [ -f "${BACKEND_DIR}/.env.example" ]; then
  DEFAULT_DB_NAME="$(grep -E '^DB_NAME=' "${BACKEND_DIR}/.env.example" | cut -d'=' -f2- | tr -d '[:space:]' || true)"
  DEFAULT_DB_USER="$(grep -E '^DB_USER=' "${BACKEND_DIR}/.env.example" | cut -d'=' -f2- | tr -d '[:space:]' || true)"
  DEFAULT_DB_PASSWORD="$(grep -E '^DB_PASSWORD=' "${BACKEND_DIR}/.env.example" | cut -d'=' -f2- | tr -d '[:space:]' || true)"
  DEFAULT_DB_HOST="$(grep -E '^DB_HOST=' "${BACKEND_DIR}/.env.example" | cut -d'=' -f2- | tr -d '[:space:]' || true)"
  DEFAULT_DB_PORT="$(grep -E '^DB_PORT=' "${BACKEND_DIR}/.env.example" | cut -d'=' -f2- | tr -d '[:space:]' || true)"
fi

export DJANGO_ENV="${DJANGO_ENV:-test}"
export DEBUG="${DEBUG:-True}"
export SECRET_KEY="${SECRET_KEY:-Fresh_PgCert_Test_Key_2026_High_Entropy_9876543210_AbCdEfGhIjKlMnOpQrStUvWxYz}"
export ALLOWED_HOSTS="${ALLOWED_HOSTS:-127.0.0.1,localhost,testserver}"
export DB_ENGINE="${DB_ENGINE:-django.db.backends.postgresql}"
export DB_NAME="${DB_NAME:-${DEFAULT_DB_NAME:-fresh_db}}"
export DB_USER="${DB_USER:-${DEFAULT_DB_USER:-fresh_user}}"
export DB_PASSWORD="${DB_PASSWORD:-${DEFAULT_DB_PASSWORD:-fresh_dev_password}}"
export DB_HOST="${DB_HOST:-${DEFAULT_DB_HOST:-127.0.0.1}}"
export DB_PORT="${DB_PORT:-${DEFAULT_DB_PORT:-5432}}"

# Normalize localhost to 127.0.0.1 for TCP socket consistency when no Unix socket is in /var/run/postgresql
if [ "${DB_HOST}" = "localhost" ]; then
  export DB_HOST="127.0.0.1"
fi

echo "==> [1/6] Checking for a running PostgreSQL service at ${DB_HOST}:${DB_PORT} (db=${DB_NAME}, user=${DB_USER})..."
if [ -x "/tmp/pg_local/usr/lib/postgresql/15/bin/pg_isready" ]; then
  /tmp/pg_local/usr/lib/postgresql/15/bin/pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}"
elif command -v pg_isready >/dev/null 2>&1; then
  pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}"
fi

python3 - <<'PY'
import os
import sys

try:
    import psycopg2
except ImportError as exc:
    sys.stderr.write(f"ERROR: psycopg2 driver is not installed ({exc}).\n")
    sys.exit(1)

host = os.environ["DB_HOST"]
port = int(os.environ["DB_PORT"])
dbname = os.environ["DB_NAME"]
user = os.environ["DB_USER"]
password = os.environ.get("DB_PASSWORD", "")

try:
    conn = psycopg2.connect(
        host=host,
        port=port,
        dbname=dbname,
        user=user,
        password=password,
        connect_timeout=5,
    )
    with conn.cursor() as cur:
        cur.execute("SELECT version();")
        version_row = cur.fetchone()[0]
    conn.close()
    print(f"    PostgreSQL connection verified: {version_row}")
except Exception as exc:
    sys.stderr.write(
        f"ERROR: Unable to connect to PostgreSQL at {host}:{port} as '{user}' on '{dbname}': {exc}\n"
        "Ensure PostgreSQL is running (e.g. `docker compose up -d postgres`).\n"
    )
    sys.exit(1)
PY

echo "==> [2/6] Running Django system check and migration drift verification..."
python3 manage.py check
python3 manage.py makemigrations --check --dry-run

echo "==> [3/6] Applying Django migrations up to orders 0004..."
python3 manage.py migrate products --noinput
python3 manage.py migrate orders 0004 --noinput

echo "==> [4/6] Verifying Migration 0005 forward, reverse (0005 -> 0004), and full migration application..."
python3 manage.py migrate orders 0005 --noinput
python3 manage.py migrate orders 0004 --noinput
python3 manage.py migrate --noinput

echo "==> [5/6] Executing targeted PostgreSQL / Django ORM tests (previously skipped without PostgreSQL)..."
python3 manage.py test \
  apps.orders.tests.MicroTask1D21PostgresORMTransactionTests \
  apps.orders.tests.MicroTask1D21HardeningAndCertificationTests \
  apps.orders.tests.MicroTask1D4OrderLifecyclePostgresTests \
  apps.orders.tests.MicroTask1D4OrderLifecycleHttpContractTests \
  apps.orders.tests.OrdersDjangoModelAndServiceTests \
  apps.orders.tests.AuthenticationAndAuthorizationDRFTests \
  apps.orders.tests.TokenLifecycleAndRateLimitingDRFTests \
  apps.orders.tests.BackendSecurityConfigurationHardeningTests \
  apps.products.tests.ProductsDjangoModelTests \
  apps.products.tests.ProductsGuestCatalogAndPermissionDRFTests \
  --verbosity 2

echo "==> [6/6] Executing full backend test suite (apps.orders & apps.products)..."
python3 manage.py test apps.orders apps.products --verbosity 2

echo "==> PostgreSQL Runtime Certification PASSED."
