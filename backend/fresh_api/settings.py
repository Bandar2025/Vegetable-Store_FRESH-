"""
Django settings for `fresh_api` — Hardened Security Configuration (Micro Task 7B-5).

Security invariants enforced at startup:
1. `DEBUG` defaults to `False` (production mode) and fails safely (`ImproperlyConfigured`)
   on invalid boolean/integer values or conflicting `DJANGO_ENV=production` + `DEBUG=True`.
2. When `DEBUG` is `False`, `SECRET_KEY` must be explicitly provided, must not use dummy or
   development placeholder values, must be at least 50 characters long, and must contain at
   least 10 distinct characters.
3. When `DEBUG` is `False`, `ALLOWED_HOSTS` must be explicitly provided, non-empty, and must
   not contain wildcards (`*`) or malformed host entries.
4. Cache configuration uses the single canonical environment variable pair `CACHE_BACKEND`
   and `CACHE_LOCATION`. `DISTRIBUTED_RATE_LIMITING_ENABLED` is `True` only when a shared
   multi-worker cache backend (e.g., `django.core.cache.backends.redis.RedisCache`) is
   configured with a valid `CACHE_LOCATION`.
5. Production HTTPS, HSTS, and secure cookie flags default to hardened values when
   `DEBUG` is `False`. Proxy/forwarded headers (`X-Forwarded-For`, `X-Forwarded-Proto`,
   `X-Forwarded-Host`) are never trusted unless explicitly enabled alongside validated
   `TRUSTED_PROXY_IPS`.
"""

from __future__ import annotations

import ipaddress
import os
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlparse

from django.core.exceptions import ImproperlyConfigured

BASE_DIR = Path(__file__).resolve().parent.parent

LOCAL_DEV_FALLBACK_SECRET_KEY = (
    'local-dev-only-insecure-key-do-not-use-in-production-0123456789abcdef'
)

FORBIDDEN_PRODUCTION_SECRET_KEYS = frozenset({
    '',
    'django-insecure-dummy-key',
    LOCAL_DEV_FALLBACK_SECRET_KEY,
    'changeme',
    'secret',
    'dummy',
    'default',
    'password',
})

FORBIDDEN_PRODUCTION_SECRET_SUBSTRINGS = (
    'django-insecure',
    'local-dev-only',
    'dummy-key',
    'changeme',
    'placeholder',
    'example-key',
    'do-not-use-in-prod',
)

MIN_PRODUCTION_SECRET_KEY_LENGTH = 50
MIN_PRODUCTION_SECRET_KEY_UNIQUE_CHARS = 10

VALID_DJANGO_ENVIRONMENTS = frozenset({
    'production',
    'prod',
    'staging',
    'development',
    'dev',
    'local',
    'test',
})

SHARED_CACHE_BACKENDS = frozenset({
    'django.core.cache.backends.redis.RedisCache',
    'django_redis.cache.RedisCache',
    'django.core.cache.backends.memcached.PyMemcacheCache',
    'django.core.cache.backends.memcached.PyLibMCCache',
})

REDIS_CACHE_BACKENDS = frozenset({
    'django.core.cache.backends.redis.RedisCache',
    'django_redis.cache.RedisCache',
})

LOCAL_DEV_CACHE_BACKEND = 'django.core.cache.backends.locmem.LocMemCache'
LOCAL_DEV_CACHE_LOCATION = 'fresh-auth-security-cache'
DUMMY_CACHE_BACKEND = 'django.core.cache.backends.dummy.DummyCache'


def parse_bool_env(
    raw_value: str | None,
    *,
    default: bool = False,
    var_name: str = 'BOOLEAN_SETTING',
) -> bool:
    """
    Strictly parses a boolean environment variable. Returns `default` when `raw_value` is
    `None` or empty, and raises `ImproperlyConfigured` on unrecognized boolean strings.
    """
    if raw_value is None:
        return default
    cleaned = str(raw_value).strip()
    if not cleaned:
        return default
    lowered = cleaned.lower()
    if lowered in ('true', '1', 'yes'):
        return True
    if lowered in ('false', '0', 'no'):
        return False
    raise ImproperlyConfigured(
        f"Invalid boolean value for {var_name}: {raw_value!r}. "
        f"Expected one of: True, False, 1, 0, yes, no."
    )


def parse_int_env(
    raw_value: str | None,
    *,
    default: int,
    min_value: int | None = None,
    var_name: str = 'INT_SETTING',
) -> int:
    """
    Strictly parses an integer environment variable and validates `min_value` when specified.
    """
    if raw_value is None or not str(raw_value).strip():
        value = default
    else:
        try:
            value = int(str(raw_value).strip())
        except (TypeError, ValueError) as exc:
            raise ImproperlyConfigured(
                f"Invalid integer value for {var_name}: {raw_value!r}."
            ) from exc
    if min_value is not None and value < min_value:
        raise ImproperlyConfigured(
            f"Configuration {var_name} must be >= {min_value} (got {value})."
        )
    return value


def parse_csv_env(raw_value: str | None) -> list[str]:
    if raw_value is None:
        return []
    return [item.strip() for item in str(raw_value).split(',') if item.strip()]


def validate_production_secret_key(secret_key: str) -> str:
    """
    Validates that a production `SECRET_KEY` is present, non-placeholder, at least
    50 characters long, and contains sufficient character diversity.
    """
    if not isinstance(secret_key, str) or not secret_key.strip():
        raise ImproperlyConfigured(
            "SECRET_KEY must be explicitly set in the environment when DEBUG is False."
        )
    cleaned = secret_key.strip()
    lowered = cleaned.lower()
    if cleaned in FORBIDDEN_PRODUCTION_SECRET_KEYS or lowered in FORBIDDEN_PRODUCTION_SECRET_KEYS:
        raise ImproperlyConfigured(
            "Insecure or dummy SECRET_KEY is not allowed when DEBUG is False."
        )
    for forbidden_sub in FORBIDDEN_PRODUCTION_SECRET_SUBSTRINGS:
        if forbidden_sub in lowered:
            raise ImproperlyConfigured(
                f"SECRET_KEY contains forbidden placeholder pattern ({forbidden_sub!r}) "
                f"and cannot be used when DEBUG is False."
            )
    if len(cleaned) < MIN_PRODUCTION_SECRET_KEY_LENGTH:
        raise ImproperlyConfigured(
            f"Production SECRET_KEY must be at least {MIN_PRODUCTION_SECRET_KEY_LENGTH} "
            f"characters long (got {len(cleaned)} characters)."
        )
    if len(set(cleaned)) < MIN_PRODUCTION_SECRET_KEY_UNIQUE_CHARS:
        raise ImproperlyConfigured(
            f"Production SECRET_KEY has insufficient entropy; requires at least "
            f"{MIN_PRODUCTION_SECRET_KEY_UNIQUE_CHARS} distinct characters."
        )
    return cleaned


def validate_allowed_hosts(
    raw_allowed_hosts: str | None,
    *,
    debug: bool,
) -> list[str]:
    """
    Validates `ALLOWED_HOSTS`. Requires explicit, non-wildcard hostnames when `DEBUG` is `False`.
    """
    hosts = parse_csv_env(raw_allowed_hosts)
    if not debug:
        if not hosts:
            raise ImproperlyConfigured(
                "ALLOWED_HOSTS must be explicitly configured with one or more hostnames "
                "when DEBUG is False."
            )
        if '*' in hosts:
            raise ImproperlyConfigured(
                "Wildcard '*' in ALLOWED_HOSTS is not permitted when DEBUG is False."
            )
    else:
        if not hosts:
            hosts = ['127.0.0.1', 'localhost']

    for host in hosts:
        if '://' in host or '/' in host or any(ch.isspace() for ch in host):
            raise ImproperlyConfigured(
                f"Invalid hostname entry in ALLOWED_HOSTS: {host!r}. "
                f"Specify hostnames/domains only without scheme, path, or whitespace."
            )
    return hosts


def parse_and_validate_trusted_proxy_ips(raw_value: str | None) -> tuple[str, ...]:
    """
    Parses and validates `TRUSTED_PROXY_IPS` as explicit IPv4/IPv6 addresses or CIDR networks.
    Wildcards or malformed entries raise `ImproperlyConfigured`.
    """
    entries = parse_csv_env(raw_value)
    validated: list[str] = []
    for entry in entries:
        if entry == '*':
            raise ImproperlyConfigured(
                "Wildcard '*' is not allowed in TRUSTED_PROXY_IPS; specify explicit proxy IPs or CIDRs."
            )
        try:
            if '/' in entry:
                net = ipaddress.ip_network(entry, strict=False)
                validated.append(str(net))
            else:
                addr = ipaddress.ip_address(entry)
                validated.append(str(addr))
        except ValueError as exc:
            raise ImproperlyConfigured(
                f"Invalid IP address or CIDR network in TRUSTED_PROXY_IPS: {entry!r}."
            ) from exc
    return tuple(validated)


def validate_and_build_cache_settings(
    env: Mapping[str, str],
    *,
    debug: bool,
) -> tuple[dict[str, dict[str, Any]], bool]:
    """
    Builds `CACHES` using the canonical `CACHE_BACKEND` and `CACHE_LOCATION` environment
    variables and returns `(caches_dict, distributed_rate_limiting_enabled)`.
    """
    if (
        ('DJANGO_CACHE_BACKEND' in env or 'DJANGO_CACHE_LOCATION' in env)
        and 'CACHE_BACKEND' not in env
        and 'CACHE_LOCATION' not in env
    ):
        raise ImproperlyConfigured(
            "Unsupported legacy cache environment variable detected "
            "(DJANGO_CACHE_BACKEND / DJANGO_CACHE_LOCATION). "
            "Use the canonical CACHE_BACKEND and CACHE_LOCATION variables."
        )

    raw_backend = env.get('CACHE_BACKEND')
    backend = (
        str(raw_backend).strip()
        if raw_backend is not None and str(raw_backend).strip()
        else LOCAL_DEV_CACHE_BACKEND
    )

    if backend == DUMMY_CACHE_BACKEND and not debug:
        raise ImproperlyConfigured(
            "DummyCache is not permitted when DEBUG is False because authentication "
            "rate limiting and token blocklisting require an active cache backend."
        )

    raw_location = env.get('CACHE_LOCATION')
    has_explicit_location = raw_location is not None and bool(str(raw_location).strip())
    location = str(raw_location).strip() if has_explicit_location else LOCAL_DEV_CACHE_LOCATION

    is_shared_backend = backend in SHARED_CACHE_BACKENDS
    if is_shared_backend:
        if not has_explicit_location:
            raise ImproperlyConfigured(
                f"CACHE_LOCATION must be explicitly set when using shared cache backend {backend!r}."
            )
        if backend in REDIS_CACHE_BACKENDS:
            parsed = urlparse(location)
            if parsed.scheme not in ('redis', 'rediss', 'unix') or (
                parsed.scheme in ('redis', 'rediss') and not parsed.hostname
            ):
                raise ImproperlyConfigured(
                    f"Invalid Redis CACHE_LOCATION URI {location!r}. "
                    f"Expected redis://<host>:<port>/<db>, rediss://<host>:<port>/<db>, or unix://<path>."
                )

    require_shared_cache = parse_bool_env(
        env.get('REQUIRE_SHARED_CACHE'),
        default=False,
        var_name='REQUIRE_SHARED_CACHE',
    )
    if require_shared_cache and not is_shared_backend:
        raise ImproperlyConfigured(
            "REQUIRE_SHARED_CACHE is True, but CACHE_BACKEND is not a shared multi-worker "
            f"cache backend (got {backend!r}). Configure RedisCache for multi-worker deployments."
        )

    caches = {
        'default': {
            'BACKEND': backend,
            'LOCATION': location,
        }
    }
    return caches, is_shared_backend


def load_runtime_settings(env: Mapping[str, str] | None = None) -> dict[str, Any]:
    """
    Validates and constructs runtime security, database, cache, proxy, and authentication
    settings from `env` (defaults to `os.environ`). Raises `ImproperlyConfigured` on any
    unsafe or invalid configuration.
    """
    source_env: Mapping[str, str] = os.environ if env is None else env

    raw_django_env = source_env.get('DJANGO_ENV')
    django_env = (
        str(raw_django_env).strip().lower()
        if raw_django_env is not None and str(raw_django_env).strip()
        else 'production'
    )
    if django_env not in VALID_DJANGO_ENVIRONMENTS:
        raise ImproperlyConfigured(
            f"Invalid DJANGO_ENV value {raw_django_env!r}. "
            f"Expected one of: {sorted(VALID_DJANGO_ENVIRONMENTS)}."
        )

    # Production DEBUG defaults to False when omitted
    debug = parse_bool_env(
        source_env.get('DEBUG'),
        default=False,
        var_name='DEBUG',
    )

    if django_env in ('production', 'prod') and raw_django_env is not None and debug:
        raise ImproperlyConfigured(
            "DEBUG cannot be True when DJANGO_ENV is explicitly set to 'production'."
        )

    raw_secret_key = source_env.get('SECRET_KEY')
    if not debug:
        secret_key = validate_production_secret_key(raw_secret_key or '')
    else:
        secret_key = (
            str(raw_secret_key).strip()
            if raw_secret_key is not None and str(raw_secret_key).strip()
            else LOCAL_DEV_FALLBACK_SECRET_KEY
        )

    allowed_hosts = validate_allowed_hosts(
        source_env.get('ALLOWED_HOSTS'),
        debug=debug,
    )

    db_port = parse_int_env(
        source_env.get('DB_PORT'),
        default=5432,
        min_value=1,
        var_name='DB_PORT',
    )
    if db_port > 65535:
        raise ImproperlyConfigured(f"Configuration DB_PORT must be <= 65535 (got {db_port}).")

    databases = {
        'default': {
            'ENGINE': source_env.get('DB_ENGINE', 'django.db.backends.postgresql'),
            'NAME': source_env.get('DB_NAME', 'fresh_db'),
            'USER': source_env.get('DB_USER', 'fresh_user'),
            'PASSWORD': source_env.get('DB_PASSWORD', ''),
            'HOST': source_env.get('DB_HOST', 'localhost'),
            'PORT': str(db_port),
        }
    }

    caches, distributed_rate_limiting_enabled = validate_and_build_cache_settings(
        source_env,
        debug=debug,
    )

    # Cookie & HTTPS security settings
    session_cookie_secure = parse_bool_env(
        source_env.get('SESSION_COOKIE_SECURE'),
        default=not debug,
        var_name='SESSION_COOKIE_SECURE',
    )
    csrf_cookie_secure = parse_bool_env(
        source_env.get('CSRF_COOKIE_SECURE'),
        default=not debug,
        var_name='CSRF_COOKIE_SECURE',
    )
    session_cookie_samesite = (source_env.get('SESSION_COOKIE_SAMESITE') or 'Lax').strip()
    csrf_cookie_samesite = (source_env.get('CSRF_COOKIE_SAMESITE') or 'Lax').strip()
    for samesite_name, samesite_val in (
        ('SESSION_COOKIE_SAMESITE', session_cookie_samesite),
        ('CSRF_COOKIE_SAMESITE', csrf_cookie_samesite),
    ):
        if samesite_val not in ('Strict', 'Lax', 'None'):
            raise ImproperlyConfigured(
                f"Invalid {samesite_name} value {samesite_val!r}. "
                f"Expected 'Strict', 'Lax', or 'None'."
            )

    secure_ssl_redirect = parse_bool_env(
        source_env.get('SECURE_SSL_REDIRECT'),
        default=not debug,
        var_name='SECURE_SSL_REDIRECT',
    )
    secure_hsts_seconds = parse_int_env(
        source_env.get('SECURE_HSTS_SECONDS'),
        default=31536000 if not debug else 0,
        min_value=0,
        var_name='SECURE_HSTS_SECONDS',
    )
    secure_hsts_include_subdomains = parse_bool_env(
        source_env.get('SECURE_HSTS_INCLUDE_SUBDOMAINS'),
        default=not debug,
        var_name='SECURE_HSTS_INCLUDE_SUBDOMAINS',
    )
    secure_hsts_preload = parse_bool_env(
        source_env.get('SECURE_HSTS_PRELOAD'),
        default=not debug,
        var_name='SECURE_HSTS_PRELOAD',
    )
    csrf_trusted_origins = parse_csv_env(source_env.get('CSRF_TRUSTED_ORIGINS'))

    if not debug:
        if not session_cookie_secure:
            raise ImproperlyConfigured(
                "SESSION_COOKIE_SECURE cannot be False when DEBUG is False."
            )
        if not csrf_cookie_secure:
            raise ImproperlyConfigured(
                "CSRF_COOKIE_SECURE cannot be False when DEBUG is False."
            )
        if secure_ssl_redirect and secure_hsts_seconds <= 0:
            raise ImproperlyConfigured(
                "SECURE_HSTS_SECONDS must be > 0 in production when SECURE_SSL_REDIRECT is True."
            )
        for origin in csrf_trusted_origins:
            if not origin.startswith('https://'):
                raise ImproperlyConfigured(
                    f"CSRF_TRUSTED_ORIGINS entry {origin!r} must use 'https://' when DEBUG is False."
                )

    # Reverse-proxy & forwarded-header settings (never trusted unless explicitly configured)
    trust_x_forwarded_proto = parse_bool_env(
        source_env.get('TRUST_X_FORWARDED_PROTO'),
        default=False,
        var_name='TRUST_X_FORWARDED_PROTO',
    )
    use_x_forwarded_host = parse_bool_env(
        source_env.get('USE_X_FORWARDED_HOST'),
        default=False,
        var_name='USE_X_FORWARDED_HOST',
    )
    trust_x_forwarded_for = parse_bool_env(
        source_env.get('TRUST_X_FORWARDED_FOR'),
        default=False,
        var_name='TRUST_X_FORWARDED_FOR',
    )
    trusted_proxy_ips = parse_and_validate_trusted_proxy_ips(
        source_env.get('TRUSTED_PROXY_IPS')
    )

    if (trust_x_forwarded_proto or use_x_forwarded_host or trust_x_forwarded_for) and not trusted_proxy_ips:
        raise ImproperlyConfigured(
            "TRUSTED_PROXY_IPS must be explicitly configured with one or more trusted "
            "reverse-proxy IP addresses or CIDR ranges before enabling "
            "TRUST_X_FORWARDED_FOR, TRUST_X_FORWARDED_PROTO, or USE_X_FORWARDED_HOST."
        )

    secure_proxy_ssl_header = (
        ('HTTP_X_FORWARDED_PROTO', 'https') if trust_x_forwarded_proto else None
    )

    # Authentication JWT, Token Lifecycle & Rate Limiting Configuration
    auth_jwt_algorithm = (source_env.get('AUTH_JWT_ALGORITHM') or 'HS256').strip()
    if auth_jwt_algorithm != 'HS256':
        raise ImproperlyConfigured(
            f"Unsupported AUTH_JWT_ALGORITHM {auth_jwt_algorithm!r}. Only 'HS256' is supported."
        )
    auth_jwt_issuer = (source_env.get('AUTH_JWT_ISSUER') or 'fresh-backend').strip()

    auth_access_token_ttl_seconds = parse_int_env(
        source_env.get('AUTH_ACCESS_TOKEN_TTL_SECONDS'),
        default=900,
        min_value=1,
        var_name='AUTH_ACCESS_TOKEN_TTL_SECONDS',
    )
    auth_refresh_token_ttl_seconds = parse_int_env(
        source_env.get('AUTH_REFRESH_TOKEN_TTL_SECONDS'),
        default=604800,
        min_value=1,
        var_name='AUTH_REFRESH_TOKEN_TTL_SECONDS',
    )
    if auth_refresh_token_ttl_seconds < auth_access_token_ttl_seconds:
        raise ImproperlyConfigured(
            "AUTH_REFRESH_TOKEN_TTL_SECONDS must be >= AUTH_ACCESS_TOKEN_TTL_SECONDS."
        )

    auth_max_failed_login_attempts = parse_int_env(
        source_env.get('AUTH_MAX_FAILED_LOGIN_ATTEMPTS'),
        default=5,
        min_value=1,
        var_name='AUTH_MAX_FAILED_LOGIN_ATTEMPTS',
    )
    auth_failed_login_window_seconds = parse_int_env(
        source_env.get('AUTH_FAILED_LOGIN_WINDOW_SECONDS'),
        default=300,
        min_value=1,
        var_name='AUTH_FAILED_LOGIN_WINDOW_SECONDS',
    )
    auth_login_rate_limit_requests = parse_int_env(
        source_env.get('AUTH_LOGIN_RATE_LIMIT_REQUESTS'),
        default=15,
        min_value=1,
        var_name='AUTH_LOGIN_RATE_LIMIT_REQUESTS',
    )
    auth_login_rate_limit_window_seconds = parse_int_env(
        source_env.get('AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS'),
        default=60,
        min_value=1,
        var_name='AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS',
    )
    auth_refresh_rate_limit_requests = parse_int_env(
        source_env.get('AUTH_REFRESH_RATE_LIMIT_REQUESTS'),
        default=10,
        min_value=1,
        var_name='AUTH_REFRESH_RATE_LIMIT_REQUESTS',
    )
    auth_refresh_rate_limit_window_seconds = parse_int_env(
        source_env.get('AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS'),
        default=60,
        min_value=1,
        var_name='AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS',
    )

    return {
        'DJANGO_ENV': django_env,
        'DEBUG': debug,
        'SECRET_KEY': secret_key,
        'ALLOWED_HOSTS': allowed_hosts,
        'DATABASES': databases,
        'CACHES': caches,
        'DISTRIBUTED_RATE_LIMITING_ENABLED': distributed_rate_limiting_enabled,
        'SESSION_COOKIE_SECURE': session_cookie_secure,
        'CSRF_COOKIE_SECURE': csrf_cookie_secure,
        'SESSION_COOKIE_HTTPONLY': True,
        'CSRF_COOKIE_HTTPONLY': True,
        'SESSION_COOKIE_SAMESITE': session_cookie_samesite,
        'CSRF_COOKIE_SAMESITE': csrf_cookie_samesite,
        'SECURE_SSL_REDIRECT': secure_ssl_redirect,
        'SECURE_HSTS_SECONDS': secure_hsts_seconds,
        'SECURE_HSTS_INCLUDE_SUBDOMAINS': secure_hsts_include_subdomains,
        'SECURE_HSTS_PRELOAD': secure_hsts_preload,
        'SECURE_CONTENT_TYPE_NOSNIFF': True,
        'SECURE_REFERRER_POLICY': (
            source_env.get('SECURE_REFERRER_POLICY') or 'same-origin'
        ).strip(),
        'X_FRAME_OPTIONS': 'DENY',
        'CSRF_TRUSTED_ORIGINS': csrf_trusted_origins,
        'TRUST_X_FORWARDED_PROTO': trust_x_forwarded_proto,
        'USE_X_FORWARDED_HOST': use_x_forwarded_host,
        'TRUST_X_FORWARDED_FOR': trust_x_forwarded_for,
        'TRUSTED_PROXY_IPS': trusted_proxy_ips,
        'SECURE_PROXY_SSL_HEADER': secure_proxy_ssl_header,
        'AUTH_JWT_ALGORITHM': auth_jwt_algorithm,
        'AUTH_JWT_ISSUER': auth_jwt_issuer,
        'AUTH_ACCESS_TOKEN_TTL_SECONDS': auth_access_token_ttl_seconds,
        'AUTH_REFRESH_TOKEN_TTL_SECONDS': auth_refresh_token_ttl_seconds,
        'AUTH_MAX_FAILED_LOGIN_ATTEMPTS': auth_max_failed_login_attempts,
        'AUTH_FAILED_LOGIN_WINDOW_SECONDS': auth_failed_login_window_seconds,
        'AUTH_LOGIN_RATE_LIMIT_REQUESTS': auth_login_rate_limit_requests,
        'AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS': auth_login_rate_limit_window_seconds,
        'AUTH_REFRESH_RATE_LIMIT_REQUESTS': auth_refresh_rate_limit_requests,
        'AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS': auth_refresh_rate_limit_window_seconds,
    }


_RUNTIME_SETTINGS = load_runtime_settings(os.environ)

DJANGO_ENV = _RUNTIME_SETTINGS['DJANGO_ENV']
DEBUG = _RUNTIME_SETTINGS['DEBUG']
SECRET_KEY = _RUNTIME_SETTINGS['SECRET_KEY']
ALLOWED_HOSTS = _RUNTIME_SETTINGS['ALLOWED_HOSTS']
DATABASES = _RUNTIME_SETTINGS['DATABASES']
CACHES = _RUNTIME_SETTINGS['CACHES']
DISTRIBUTED_RATE_LIMITING_ENABLED = _RUNTIME_SETTINGS['DISTRIBUTED_RATE_LIMITING_ENABLED']

SESSION_COOKIE_SECURE = _RUNTIME_SETTINGS['SESSION_COOKIE_SECURE']
CSRF_COOKIE_SECURE = _RUNTIME_SETTINGS['CSRF_COOKIE_SECURE']
SESSION_COOKIE_HTTPONLY = _RUNTIME_SETTINGS['SESSION_COOKIE_HTTPONLY']
CSRF_COOKIE_HTTPONLY = _RUNTIME_SETTINGS['CSRF_COOKIE_HTTPONLY']
SESSION_COOKIE_SAMESITE = _RUNTIME_SETTINGS['SESSION_COOKIE_SAMESITE']
CSRF_COOKIE_SAMESITE = _RUNTIME_SETTINGS['CSRF_COOKIE_SAMESITE']
SECURE_SSL_REDIRECT = _RUNTIME_SETTINGS['SECURE_SSL_REDIRECT']
SECURE_HSTS_SECONDS = _RUNTIME_SETTINGS['SECURE_HSTS_SECONDS']
SECURE_HSTS_INCLUDE_SUBDOMAINS = _RUNTIME_SETTINGS['SECURE_HSTS_INCLUDE_SUBDOMAINS']
SECURE_HSTS_PRELOAD = _RUNTIME_SETTINGS['SECURE_HSTS_PRELOAD']
SECURE_CONTENT_TYPE_NOSNIFF = _RUNTIME_SETTINGS['SECURE_CONTENT_TYPE_NOSNIFF']
SECURE_REFERRER_POLICY = _RUNTIME_SETTINGS['SECURE_REFERRER_POLICY']
X_FRAME_OPTIONS = _RUNTIME_SETTINGS['X_FRAME_OPTIONS']
CSRF_TRUSTED_ORIGINS = _RUNTIME_SETTINGS['CSRF_TRUSTED_ORIGINS']

TRUST_X_FORWARDED_PROTO = _RUNTIME_SETTINGS['TRUST_X_FORWARDED_PROTO']
USE_X_FORWARDED_HOST = _RUNTIME_SETTINGS['USE_X_FORWARDED_HOST']
TRUST_X_FORWARDED_FOR = _RUNTIME_SETTINGS['TRUST_X_FORWARDED_FOR']
TRUSTED_PROXY_IPS = _RUNTIME_SETTINGS['TRUSTED_PROXY_IPS']
SECURE_PROXY_SSL_HEADER = _RUNTIME_SETTINGS['SECURE_PROXY_SSL_HEADER']

AUTH_JWT_ALGORITHM = _RUNTIME_SETTINGS['AUTH_JWT_ALGORITHM']
AUTH_JWT_ISSUER = _RUNTIME_SETTINGS['AUTH_JWT_ISSUER']
AUTH_ACCESS_TOKEN_TTL_SECONDS = _RUNTIME_SETTINGS['AUTH_ACCESS_TOKEN_TTL_SECONDS']
AUTH_REFRESH_TOKEN_TTL_SECONDS = _RUNTIME_SETTINGS['AUTH_REFRESH_TOKEN_TTL_SECONDS']
AUTH_MAX_FAILED_LOGIN_ATTEMPTS = _RUNTIME_SETTINGS['AUTH_MAX_FAILED_LOGIN_ATTEMPTS']
AUTH_FAILED_LOGIN_WINDOW_SECONDS = _RUNTIME_SETTINGS['AUTH_FAILED_LOGIN_WINDOW_SECONDS']
AUTH_LOGIN_RATE_LIMIT_REQUESTS = _RUNTIME_SETTINGS['AUTH_LOGIN_RATE_LIMIT_REQUESTS']
AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS = _RUNTIME_SETTINGS['AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS']
AUTH_REFRESH_RATE_LIMIT_REQUESTS = _RUNTIME_SETTINGS['AUTH_REFRESH_RATE_LIMIT_REQUESTS']
AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS = _RUNTIME_SETTINGS['AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS']

INSTALLED_APPS = [
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    'rest_framework',
    'rest_framework.authtoken',
    'apps.products',
    'apps.orders',
    'core',
]

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'middleware.auth_rate_limit.AuthRateLimitMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
]

ROOT_URLCONF = 'fresh_api.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.debug',
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
            ],
        },
    },
]

WSGI_APPLICATION = 'fresh_api.wsgi.application'

AUTH_PASSWORD_VALIDATORS = [
    {
        'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator',
    },
]

REST_FRAMEWORK = {
    'DEFAULT_AUTHENTICATION_CLASSES': [
        'apps.orders.auth.ExpiringTokenAuthentication',
        'rest_framework.authentication.SessionAuthentication',
    ],
    'DEFAULT_PERMISSION_CLASSES': [
        'rest_framework.permissions.IsAuthenticated',
    ],
}

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

LANGUAGE_CODE = 'ar-sa'
TIME_ZONE = 'UTC'
USE_I18N = True
USE_TZ = True
