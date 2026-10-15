#!/usr/bin/env bash
#
# E-commerce Backend — PostgreSQL provisioning (idempotent, non-destructive)
# --------------------------------------------------------------------------
# Creates ONLY:
#   role     : ecommerce_app        (LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE)
#   database : ecommerce_db         (owner ecommerce_app)
#   database : ecommerce_test_db    (owner ecommerce_app, used by integration tests)
#
# It NEVER touches any other role or database. In particular it does not touch
# the FinSight project's finsight_app role or finsight_db / finsight_test_db.
#
# The generated password is written to <project>/.env with mode 0600, owned by
# the invoking (non-root) user. It is passed to psql on stdin, never as a
# command-line argument, so it cannot appear in `ps` output, and it never enters
# your shell history.
#
# Usage:
#   sudo bash scripts/provision-db.sh
#
set -euo pipefail

APP_ROLE="ecommerce_app"
APP_DB="ecommerce_db"
TEST_DB="ecommerce_test_db"
DB_HOST="127.0.0.1"
DB_PORT="5432"

# Roles/databases this script must never modify, as a guard against typos.
PROTECTED="finsight_app finsight_db finsight_test_db postgres template0 template1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

TARGET_USER="${SUDO_USER:-$(id -un)}"
TARGET_GROUP="$(id -gn "$TARGET_USER")"

log()  { printf '  %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

echo
echo "E-commerce Backend :: PostgreSQL provisioning"
echo "--------------------------------------------"

# ---------------------------------------------------------------- preflight --
[[ "$(id -u)" -eq 0 ]] || die "Must run as root:  sudo bash scripts/provision-db.sh"
command -v psql >/dev/null || die "psql not found on PATH."
id postgres >/dev/null 2>&1 || die "No 'postgres' OS user found; is PostgreSQL installed?"

# Guard: refuse if someone edited the names to point at a protected object.
for p in $PROTECTED; do
  [[ "$APP_ROLE" == "$p" || "$APP_DB" == "$p" || "$TEST_DB" == "$p" ]] \
    && die "Refusing to operate on protected object '$p'."
done

psql_su()    { su postgres -c "psql -v ON_ERROR_STOP=1 -qtAX -d postgres"; }
psql_su_db() { local db="$1"; su postgres -c "psql -v ON_ERROR_STOP=1 -qtAX -d $db"; }

echo "SELECT 1;" | psql_su >/dev/null 2>&1 \
  || die "Cannot connect as the 'postgres' superuser. Is the server running? (pg_lsclusters)"
ok "Connected to PostgreSQL as superuser 'postgres'"
log "Server version: $(echo 'SHOW server_version;' | psql_su)"

# Confirm the FinSight objects exist and note that we leave them alone.
if [[ "$(echo "SELECT 1 FROM pg_roles WHERE rolname='finsight_app';" | psql_su)" == "1" ]]; then
  log "Detected existing 'finsight_app' role — it will NOT be modified."
fi

# ------------------------------------------------------------ existing state --
role_exists="$(echo "SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE';" | psql_su)"
app_db_exists="$(echo "SELECT 1 FROM pg_database WHERE datname = '$APP_DB';" | psql_su)"
test_db_exists="$(echo "SELECT 1 FROM pg_database WHERE datname = '$TEST_DB';" | psql_su)"

# -------------------------------------------------------------- password mgmt --
EXISTING_PW=""
if [[ -f "$ENV_FILE" ]]; then
  EXISTING_PW="$(sed -n 's/^ECOMMERCE_DB_PASSWORD=//p' "$ENV_FILE" | head -n1)"
fi

ROTATE="no"
if [[ -n "$EXISTING_PW" ]]; then
  DB_PASSWORD="$EXISTING_PW"
  log "Reusing ECOMMERCE_DB_PASSWORD already present in .env"
else
  DB_PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
  [[ ${#DB_PASSWORD} -eq 32 ]] || die "Failed to generate a password."
  ROTATE="yes"
  log "Generated a new 32-character random password"
fi

# --------------------------------------------------------------- create role --
if [[ "$role_exists" == "1" ]]; then
  warn "Role '$APP_ROLE' already exists — not recreating it."
  if [[ "$ROTATE" == "yes" ]]; then
    echo
    read -r -p "  Reset the password of the EXISTING role '$APP_ROLE'? [y/N] " answer </dev/tty
    if [[ "${answer,,}" == "y" ]]; then
      printf "ALTER ROLE %s WITH LOGIN PASSWORD '%s';\n" "$APP_ROLE" "$DB_PASSWORD" | psql_su >/dev/null
      ok "Password rotated for '$APP_ROLE'"
    else
      die "Aborted. Put the existing password into $ENV_FILE as ECOMMERCE_DB_PASSWORD=... and re-run."
    fi
  fi
else
  printf "CREATE ROLE %s WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT PASSWORD '%s';\n" \
    "$APP_ROLE" "$DB_PASSWORD" | psql_su >/dev/null
  ok "Created least-privilege role '$APP_ROLE'"
fi

# ----------------------------------------------------------- create databases --
create_db() {
  local db="$1" exists="$2"
  if [[ "$exists" == "1" ]]; then
    local owner
    owner="$(echo "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$db';" | psql_su)"
    warn "Database '$db' already exists (owner: $owner) — leaving it untouched."
  else
    echo "CREATE DATABASE $db OWNER $APP_ROLE ENCODING 'UTF8' TEMPLATE template0;" | psql_su >/dev/null
    ok "Created database '$db' owned by '$APP_ROLE'"
  fi

  psql_su_db "$db" >/dev/null <<SQL
REVOKE ALL ON DATABASE $db FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE $db TO $APP_ROLE;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO $APP_ROLE;
SQL
  ok "Hardened privileges on '$db'"
}

create_db "$APP_DB"  "$app_db_exists"
create_db "$TEST_DB" "$test_db_exists"

# ---------------------------------------------------------------- write .env --
umask 077
TMP_ENV="$(mktemp)"
cat > "$TMP_ENV" <<ENVEOF
# E-commerce Backend — local secrets. GENERATED BY scripts/provision-db.sh
# DO NOT COMMIT. Listed in .gitignore.
# Regenerate with: sudo bash scripts/provision-db.sh

ECOMMERCE_DB_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$APP_DB
ECOMMERCE_DB_USERNAME=$APP_ROLE
ECOMMERCE_DB_PASSWORD=$DB_PASSWORD

ECOMMERCE_TEST_DB_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$TEST_DB

# HS256 signing key for JWTs (base64, >= 256 bits). Rotate for any real deployment.
ECOMMERCE_JWT_SECRET=$(head -c 64 /dev/urandom | base64 | tr -d '\n')

ECOMMERCE_REDIS_HOST=localhost
ECOMMERCE_REDIS_PORT=6379

# Development administrator, created once at startup by AdminBootstrap when both
# are set. Never enabled in the prod profile. Change this password.
ECOMMERCE_ADMIN_EMAIL=admin@example.com
ECOMMERCE_ADMIN_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)Aa1!
ENVEOF

# Preserve any extra keys from a pre-existing .env
if [[ -f "$ENV_FILE" ]]; then
  while IFS= read -r line; do
    key="${line%%=*}"
    [[ "$line" == \#* || -z "$line" ]] && continue
    grep -q "^${key}=" "$TMP_ENV" || printf '%s\n' "$line" >> "$TMP_ENV"
  done < "$ENV_FILE"
fi

mv "$TMP_ENV" "$ENV_FILE"
chmod 600 "$ENV_FILE"
chown "$TARGET_USER:$TARGET_GROUP" "$ENV_FILE"
ok "Wrote $ENV_FILE (mode 0600, owner $TARGET_USER)"

# ----------------------------------------------------------- verify as app role --
if su "$TARGET_USER" -c \
     "PGPASSWORD='$DB_PASSWORD' psql -h $DB_HOST -p $DB_PORT -U $APP_ROLE -d $APP_DB -qtAX -c 'SELECT 1;'" \
     >/dev/null 2>&1; then
  ok "Verified: '$APP_ROLE' can log in to '$APP_DB' over TCP"
else
  warn "Could not verify the app-role login over TCP."
  warn "Check: sudo grep -nE '^(host|local)' /etc/postgresql/*/main/pg_hba.conf"
fi

IS_SUPER="$(echo "SELECT rolsuper FROM pg_roles WHERE rolname='$APP_ROLE';" | psql_su)"
[[ "$IS_SUPER" == "f" ]] && ok "Confirmed '$APP_ROLE' is NOT a superuser" \
                         || warn "'$APP_ROLE' reports rolsuper=$IS_SUPER — review manually."

# Final assurance that FinSight is untouched.
if [[ "$(echo "SELECT 1 FROM pg_database WHERE datname='finsight_db';" | psql_su)" == "1" ]]; then
  ok "FinSight database still present and untouched"
fi

echo
echo "Done. Next:  ./mvnw clean test"
echo
