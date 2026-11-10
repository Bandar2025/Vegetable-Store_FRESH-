# FRESH Security Architecture & Production Configuration Guide (`SECURITY.md`)

This document specifies the JWT authentication token lifecycle, the `AUTH_MAX_FAILED_LOGIN_ATTEMPTS` brute-force protection policy, and the mandatory environment variable configuration required for secure production deployments of the FRESH platform.

---

## 1. JWT Token Lifecycle (15m Access / 7d Refresh)

FRESH uses a stateful, rotation-enforced JWT authentication architecture that combines cryptographic verification with server-side session tracking and immediate revocation:

### Token Types & Expiration (TTL)
| Token Type | Setting Name | Default TTL | Purpose |
| :--- | :--- | :--- | :--- |
| **Access Token** | `AUTH_ACCESS_TOKEN_TTL_SECONDS` | **15 minutes** (`900` seconds) | Short-lived bearer token for authenticating API requests (`Authorization: Bearer <token>`). |
| **Refresh Token** | `AUTH_REFRESH_TOKEN_TTL_SECONDS` | **7 days** (`604800` seconds) | Single-use credential used exclusively at `POST /api/v1/auth/refresh/` to obtain a new token pair. |

### Cryptographic Signing & Hashed Storage
- **Signing Algorithm**: Tokens are signed using `HMAC-SHA256` (`HS256`) with a signing key derived from the application's `SECRET_KEY`.
- **Zero Plaintext Persistence**: Raw JWT strings are returned to the client upon login (`POST /api/v1/auth/login/`) or refresh (`POST /api/v1/auth/refresh/`) and are **never stored in plaintext** in the database.
- **Database Digest Tracking**: Only the SHA-256 hex digests (`access_token_hash`, `refresh_token_hash`) and token family lineage (`token_family_id`, `parent_token_id`) are persisted in `AuthSessionToken`.

### Single-Use Refresh Rotation & Replay Detection
- **Strict Rotation**: Every call to `POST /api/v1/auth/refresh/` consumes the presented refresh token, marks it as rotated, blocklists its hash in cache, and issues a fresh `(access, refresh)` token pair within the same `token_family_id`.
- **Replay / Reuse Detection**: If a previously rotated refresh token is presented again, the backend treats the event as a token compromise (`REUSE_DETECTED`) and **immediately revokes the entire `token_family_id`**, invalidating all active access and refresh tokens in that lineage.
- **Explicit Logout (`POST /api/v1/auth/logout/`)**: Immediately revokes the current token family (or all active sessions for the user when `all_devices=True`) in both PostgreSQL/SQLite and the Django cache blocklist.
- **Anti-Enumeration**: Failed authentication requests (unknown username/email, wrong password, or inactive account) return an identical `401 Unauthorized` response (`{"detail": "Invalid authentication credentials."}`).

---

## 2. `AUTH_MAX_FAILED_LOGIN_ATTEMPTS` Policy & Rate Limiting

Login endpoints (`/api/v1/auth/login/` and `/api/v1/auth/token/`) are protected against credential stuffing and brute-force attacks by `AuthRateLimitMiddleware` (`backend/middleware/auth_rate_limit.py`) and the authentication service layer:

### Policy Thresholds
- **`AUTH_MAX_FAILED_LOGIN_ATTEMPTS`**: Defaults to **`5`** failed attempts.
- **`AUTH_FAILED_LOGIN_WINDOW_SECONDS`**: Defaults to **`300` seconds** (5-minute sliding window).
- **Endpoint Request Limits**:
  - `AUTH_LOGIN_RATE_LIMIT_REQUESTS`: **`15`** requests per `60`-second window (`AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS = 60`).
  - `AUTH_REFRESH_RATE_LIMIT_REQUESTS`: **`10`** requests per `60`-second window (`AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS = 60`).

### Enforcement Mechanism
1. **Pre-Authentication Check**: Before processing a `POST` request to a login endpoint, `AuthRateLimitMiddleware` inspects the failed-attempt counter in `django.core.cache` keyed by the normalized client IP (`auth_rate_limit:ip:<ip>`).
2. **Lockout Response (`429 Too Many Requests`)**: When the counter is greater than or equal to `AUTH_MAX_FAILED_LOGIN_ATTEMPTS`, the request is rejected immediately with HTTP `429 Too Many Requests` and a `Retry-After` header specifying the remaining lockout window in seconds.
3. **Failure Recording**: Authentication responses returning HTTP `400`, `401`, or `403` increment the client IP's failed-attempt counter in cache (initializing the TTL to `AUTH_FAILED_LOGIN_WINDOW_SECONDS`) and record an audit entry in `AuthThrottleEvent`.
4. **Reset on Success**: A successful login (`200 OK`) clears the failed-attempt counter for that client IP.
5. **Spoofing-Resistant Client IP Resolution**:
   - `X-Forwarded-For` is **ignored by default** (`TRUST_X_FORWARDED_FOR=False`).
   - Forwarded headers are only trusted when `TRUST_X_FORWARDED_FOR=True` **and** the immediate peer (`REMOTE_ADDR`) matches an explicitly configured IP or CIDR block in `TRUSTED_PROXY_IPS`.

---

## 3. Mandatory Production Environment Variable Requirements

Startup configuration in `backend/fresh_api/settings.py` and Django system checks in `backend/core/checks.py` (`fresh.E001`–`fresh.E007`) enforce fail-closed validation when `DEBUG=False` or `DJANGO_ENV=production`:

### Required Core Variables
| Variable | Production Requirement | Validation Rule |
| :--- | :--- | :--- |
| `DJANGO_ENV` | `production` | Selects production defaults (`DEBUG=False`). |
| `DEBUG` | `False` | Must be `False` in production. Setting `DEBUG=True` when `DJANGO_ENV=production` raises `ImproperlyConfigured`. |
| `SECRET_KEY` | High-entropy secret string | Required when `DEBUG=False`. Must be `>= 50` characters, contain `>= 10` unique characters, and must not match any default/placeholder pattern (`django-insecure`, `changeme`, `dummy`, `local-dev-only`). |
| `ALLOWED_HOSTS` | Comma-separated hostnames (e.g. `api.fresh.sa`) | Required and non-empty when `DEBUG=False`. Wildcard `*` is forbidden in production. |

### Database & Shared Cache Variables (Multi-Worker Production)
| Variable | Production Requirement | Notes |
| :--- | :--- | :--- |
| `DB_ENGINE` | `django.db.backends.postgresql` | Authoritative relational store (`DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_HOST`, `DB_PORT`). |
| `CACHE_BACKEND` | `django.core.cache.backends.redis.RedisCache` | Canonical cache backend variable. Must use a shared cache (Redis) in multi-worker deployments so rate-limiting counters and token blocklists are enforced across all workers. |
| `CACHE_LOCATION` | `redis://<host>:<port>/<db>` | Canonical Redis connection URL for the shared cache backend. |

### Proxy, HTTPS & Cookie Hardening Variables
| Variable | Production Default / Requirement | Description |
| :--- | :--- | :--- |
| `SECURE_SSL_REDIRECT` | `True` (when `DEBUG=False`) | Redirects all HTTP traffic to HTTPS. |
| `SESSION_COOKIE_SECURE` | `True` (when `DEBUG=False`) | Ensures session cookies are transmitted only over HTTPS. |
| `CSRF_COOKIE_SECURE` | `True` (when `DEBUG=False`) | Ensures CSRF cookies are transmitted only over HTTPS. |
| `SECURE_HSTS_SECONDS` | `31536000` (1 year) | Enables HTTP Strict Transport Security (`SECURE_HSTS_INCLUDE_SUBDOMAINS=True`, `SECURE_HSTS_PRELOAD=True`). |
| `TRUST_X_FORWARDED_FOR` | `False` (unless behind trusted proxy) | If `True`, `TRUSTED_PROXY_IPS` **must** also be set to a non-empty comma-separated list of proxy IPs/CIDRs. |
| `TRUST_X_FORWARDED_PROTO` | `False` (unless behind TLS-terminating proxy) | Enables `SECURE_PROXY_SSL_HEADER = ('HTTP_X_FORWARDED_PROTO', 'https')` only when explicitly enabled. |
