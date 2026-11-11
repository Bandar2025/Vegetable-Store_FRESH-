"""
Django middleware (`middleware.auth_rate_limit`) using the Django cache framework
(`django.core.cache`) to track and enforce `AUTH_MAX_FAILED_LOGIN_ATTEMPTS` (within
`AUTH_FAILED_LOGIN_WINDOW_SECONDS`) for login endpoints based on the client's IP address.
"""

from __future__ import annotations

import ipaddress
import math
from datetime import datetime, timedelta, timezone as dt_timezone
from typing import Callable

try:
    from django.conf import settings
    from django.core.cache import cache
    from django.http import HttpRequest, JsonResponse
    from django.utils import timezone
    from rest_framework import status

    DJANGO_AVAILABLE = True
except ImportError:
    DJANGO_AVAILABLE = False

    class _FallbackSettings:
        SECRET_KEY = 'local-dev-only-f9a8b7c6d5e4f3a2b1c09876543210fedcba9876543210fedcba9876543210'
        AUTH_JWT_SIGNING_KEY = SECRET_KEY
        AUTH_JWT_ALGORITHM = 'HS256'
        AUTH_JWT_ISSUER = 'fresh-backend'
        AUTH_ACCESS_TOKEN_TTL_SECONDS = 900
        AUTH_REFRESH_TOKEN_TTL_SECONDS = 604800
        AUTH_MAX_FAILED_LOGIN_ATTEMPTS = 5
        AUTH_FAILED_LOGIN_WINDOW_SECONDS = 300
        AUTH_LOGIN_RATE_LIMIT_REQUESTS = 15
        AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS = 60
        AUTH_REFRESH_RATE_LIMIT_REQUESTS = 10
        AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS = 60
        TRUST_X_FORWARDED_FOR = False
        TRUSTED_PROXY_IPS = ()

    class _FallbackCache:
        def __init__(self):
            self._store: dict[str, object] = {}

        def get(self, key: str, default=None):
            return self._store.get(key, default)

        def set(self, key: str, value: object, timeout: int | None = None) -> None:
            self._store[key] = value

        def delete(self, key: str) -> None:
            self._store.pop(key, None)

        def delete_many(self, keys) -> None:
            for k in keys:
                self._store.pop(k, None)

        def clear(self) -> None:
            self._store.clear()

    class _FallbackTimezone:
        @staticmethod
        def now() -> datetime:
            return datetime.now(dt_timezone.utc)

    class HttpRequest:  # type: ignore[no-redef]
        def __init__(self, method: str = 'POST', path: str = '', meta: dict | None = None, body: bytes = b''):
            self.method = method
            self.path = path
            self.path_info = path
            self.META = meta or {}
            self.body = body

    class JsonResponse(dict):  # type: ignore[no-redef]
        def __init__(self, data: dict, status: int = 200, headers: dict | None = None):
            super().__init__(data)
            self.data = data
            self.status_code = status
            self.headers: dict[str, str] = dict(headers or {})

        def __setitem__(self, key: str, value: object) -> None:
            if isinstance(key, str) and key.lower() == 'retry-after':
                self.headers[key] = str(value)
            super().__setitem__(key, value)

        def __contains__(self, key: object) -> bool:
            if isinstance(key, str) and key in self.headers:
                return True
            return super().__contains__(key)

    class _FallbackStatus:
        HTTP_200_OK = 200
        HTTP_201_CREATED = 201
        HTTP_400_BAD_REQUEST = 400
        HTTP_401_UNAUTHORIZED = 401
        HTTP_403_FORBIDDEN = 403
        HTTP_429_TOO_MANY_REQUESTS = 429

    settings = _FallbackSettings()
    cache = _FallbackCache()
    timezone = _FallbackTimezone()
    status = _FallbackStatus()

CACHE_PREFIX_RL_LOGIN_FAIL_IP = 'auth:ratelimit:login_fail:ip:'
CACHE_KEY_RL_TRACKED_KEYS = 'auth:ratelimit:tracked_keys'

LOGIN_RATE_LIMITED_PATHS = frozenset({
    '/api/v1/auth/login/',
    '/api/v1/auth/login',
    '/api/v1/auth/token/',
    '/api/v1/auth/token',
    '/api/v1/auth/register/',
    '/api/v1/auth/register',
})

REFRESH_RATE_LIMITED_PATHS = frozenset({
    '/api/v1/auth/refresh/',
    '/api/v1/auth/refresh',
    '/api/v1/auth/token/refresh/',
    '/api/v1/auth/token/refresh',
    '/api/v1/auth/renew/',
    '/api/v1/auth/renew',
})

LOGIN_ENDPOINT_URL_NAMES = frozenset({
    'auth-login',
    'auth-token',
    'auth-register',
    'login',
})


def _is_ip_in_trusted_proxies(ip_str: str, trusted_proxies: tuple[str, ...] | list[str]) -> bool:
    if not ip_str or not trusted_proxies:
        return False
    try:
        candidate_ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return False
    for entry in trusted_proxies:
        cleaned = str(entry).strip()
        if not cleaned:
            continue
        try:
            if '/' in cleaned:
                if candidate_ip in ipaddress.ip_network(cleaned, strict=False):
                    return True
            else:
                if candidate_ip == ipaddress.ip_address(cleaned):
                    return True
        except ValueError:
            continue
    return False


def extract_client_ip(request: HttpRequest) -> str:
    """
    Extracts the client IP address safely:
    - Always defaults to `REMOTE_ADDR` and ignores arbitrary `HTTP_X_FORWARDED_FOR` headers.
    - Only inspects `HTTP_X_FORWARDED_FOR` when `settings.TRUST_X_FORWARDED_FOR` is `True`
      AND `REMOTE_ADDR` belongs to `settings.TRUSTED_PROXY_IPS`.
    - When `REMOTE_ADDR` is an explicitly trusted proxy, traverses `HTTP_X_FORWARDED_FOR`
      from right-to-left (nearest hop to farthest hop), skipping trusted proxy hops and
      returning the first valid non-proxy client IP address (prevents left-side XFF spoofing).
    """
    meta = getattr(request, 'META', None)
    if not isinstance(meta, dict):
        underlying = getattr(request, '_request', None)
        meta = getattr(underlying, 'META', {}) if underlying is not None else {}

    raw_remote = meta.get('REMOTE_ADDR')
    remote_addr = raw_remote.strip()[:100] if isinstance(raw_remote, str) and raw_remote.strip() else ''

    trust_xff = bool(getattr(settings, 'TRUST_X_FORWARDED_FOR', False))
    trusted_proxies = tuple(getattr(settings, 'TRUSTED_PROXY_IPS', ()) or ())

    if trust_xff and remote_addr and _is_ip_in_trusted_proxies(remote_addr, trusted_proxies):
        xff = meta.get('HTTP_X_FORWARDED_FOR')
        if isinstance(xff, str) and xff.strip():
            hops = [hop.strip() for hop in xff.split(',') if hop.strip()]
            for candidate in reversed(hops):
                try:
                    parsed_ip = str(ipaddress.ip_address(candidate))
                except ValueError:
                    continue
                if not _is_ip_in_trusted_proxies(parsed_ip, trusted_proxies):
                    return parsed_ip[:100]
            # If all hops in XFF are trusted proxies, return the left-most validated hop
            for candidate in hops:
                try:
                    return str(ipaddress.ip_address(candidate))[:100]
                except ValueError:
                    continue

    if remote_addr:
        return remote_addr
    return 'unknown'


def _compute_retry_after_seconds(
    oldest_event_time: datetime | float | None,
    window_seconds: int,
    now: datetime,
) -> int:
    if oldest_event_time is None:
        return max(window_seconds, 1)
    if isinstance(oldest_event_time, (int, float)):
        remaining = (float(oldest_event_time) + float(window_seconds)) - now.timestamp()
    else:
        remaining = (oldest_event_time + timedelta(seconds=window_seconds) - now).total_seconds()
    return max(int(math.ceil(remaining)), 1)


def _register_tracked_rate_limit_cache_key(cache_key: str, window_seconds: int) -> None:
    tracked = cache.get(CACHE_KEY_RL_TRACKED_KEYS)
    if not isinstance(tracked, set):
        tracked = set()
    if cache_key not in tracked:
        tracked.add(cache_key)
        cache.set(CACHE_KEY_RL_TRACKED_KEYS, tracked, timeout=max(window_seconds * 2, 600))


def _get_cache_sliding_window_timestamps(
    cache_key: str,
    window_seconds: int,
    now_ts: float,
) -> list[float]:
    """
    Reads and prunes sliding-window attempt timestamps in `django.core.cache`
    for `cache_key` within `window_seconds`.
    """
    raw_list = cache.get(cache_key)
    if not isinstance(raw_list, list):
        return []
    cutoff_ts = now_ts - float(window_seconds)
    pruned = [
        float(ts)
        for ts in raw_list
        if isinstance(ts, (int, float)) and float(ts) >= cutoff_ts
    ]
    if len(pruned) != len(raw_list):
        if pruned:
            cache.set(cache_key, pruned, timeout=window_seconds)
        else:
            cache.delete(cache_key)
    return pruned


def _append_cache_sliding_window_timestamp(
    cache_key: str,
    window_seconds: int,
    now_ts: float,
) -> list[float]:
    timestamps = _get_cache_sliding_window_timestamps(cache_key, window_seconds, now_ts)
    timestamps.append(now_ts)
    cache.set(cache_key, timestamps, timeout=window_seconds)
    _register_tracked_rate_limit_cache_key(cache_key, window_seconds)
    return timestamps


def get_client_ip_failed_login_count(client_ip: str) -> int:
    """
    Returns the active failed login count stored in `django.core.cache` for `client_ip`
    within `settings.AUTH_FAILED_LOGIN_WINDOW_SECONDS`.
    """
    if not isinstance(client_ip, str) or not client_ip.strip():
        return 0
    now_ts = timezone.now().timestamp()
    failed_window = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)
    cache_key = f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip.strip()[:100]}"
    return len(_get_cache_sliding_window_timestamps(cache_key, failed_window, now_ts))


def record_failed_login_attempt(request: HttpRequest, username: str | None = None) -> None:
    """
    Delegates to `apps.orders.auth.record_failed_login_attempt` to record the failed login
    attempt in `django.core.cache` by client IP (and normalized username) and persist the
    audit event. Falls back to direct cache recording if called before app registry load.
    """
    try:
        from apps.orders.auth import record_failed_login_attempt as _auth_record_failed

        _auth_record_failed(request, username=username)
    except Exception:
        underlying_req = getattr(request, '_request', request)
        if underlying_req is not None and getattr(underlying_req, '_auth_failed_login_recorded', False):
            return
        now_ts = timezone.now().timestamp()
        failed_window = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)
        client_ip = extract_client_ip(request)
        _append_cache_sliding_window_timestamp(
            f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip}",
            failed_window,
            now_ts,
        )
        if underlying_req is not None:
            setattr(underlying_req, '_auth_failed_login_recorded', True)


def clear_failed_login_attempts(request: HttpRequest, username: str | None = None) -> None:
    """
    Clears the client IP's failed login counter in `django.core.cache` upon a successful login.
    """
    try:
        from apps.orders.auth import clear_failed_login_attempts as _auth_clear_failed

        _auth_clear_failed(request, username=username)
    except Exception:
        underlying_req = getattr(request, '_request', request)
        client_ip = extract_client_ip(request)
        cache.delete(f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip}")
        if underlying_req is not None:
            setattr(underlying_req, '_auth_failed_login_cleared', True)


class AuthRateLimitMiddleware:
    """
    Django middleware using the Django cache framework (`django.core.cache`) to enforce
    `AUTH_MAX_FAILED_LOGIN_ATTEMPTS` (within `AUTH_FAILED_LOGIN_WINDOW_SECONDS`) for login
    endpoints based on the client's IP address:
    - Pre-request check: inspects the sliding-window list of failed login timestamps in
      `django.core.cache` under `auth:ratelimit:login_fail:ip:<client_ip>`. When the count
      reaches `settings.AUTH_MAX_FAILED_LOGIN_ATTEMPTS`, immediately rejects the request
      with HTTP `429 Too Many Requests` and a `Retry-After` header.
    - Post-response tracking: when a login endpoint responds with `400`, `401`, or `403`,
      records the failure timestamp for the client's IP in `django.core.cache`; when a login
      succeeds (`2xx`), clears the client IP's failed-login counter in `django.core.cache`.
    """

    def __init__(self, get_response: Callable):
        self.get_response = get_response

    @staticmethod
    def get_client_ip(request: HttpRequest) -> str:
        return extract_client_ip(request)

    @staticmethod
    def get_cache_key_for_ip(client_ip: str) -> str:
        cleaned_ip = (client_ip or 'unknown').strip()[:100] or 'unknown'
        return f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{cleaned_ip}"

    @classmethod
    def get_failed_attempts_for_ip(
        cls,
        client_ip: str,
        *,
        now: datetime | None = None,
    ) -> list[float]:
        effective_now = now or timezone.now()
        window_seconds = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)
        cache_key = cls.get_cache_key_for_ip(client_ip)
        return _get_cache_sliding_window_timestamps(
            cache_key,
            window_seconds,
            effective_now.timestamp(),
        )

    @classmethod
    def is_ip_locked_out(
        cls,
        client_ip: str,
        *,
        now: datetime | None = None,
    ) -> tuple[bool, int]:
        effective_now = now or timezone.now()
        max_failed = max(int(getattr(settings, 'AUTH_MAX_FAILED_LOGIN_ATTEMPTS', 5)), 1)
        window_seconds = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)
        failures = cls.get_failed_attempts_for_ip(client_ip, now=effective_now)
        if len(failures) >= max_failed:
            retry_after = _compute_retry_after_seconds(
                failures[0],
                window_seconds,
                effective_now,
            )
            return True, retry_after
        return False, 0

    @staticmethod
    def is_login_endpoint(request: HttpRequest) -> bool:
        path = (getattr(request, 'path_info', None) or getattr(request, 'path', '') or '').strip()
        if path in LOGIN_RATE_LIMITED_PATHS:
            return True
        normalized = path.rstrip('/')
        if normalized in ('/api/v1/auth/login', '/api/v1/auth/token', '/api/v1/auth/register'):
            return True
        try:
            from django.urls import resolve

            match = resolve(path)
            if match.url_name in LOGIN_ENDPOINT_URL_NAMES:
                return True
        except Exception:
            pass
        return False

    @staticmethod
    def is_refresh_endpoint(request: HttpRequest) -> bool:
        path = (getattr(request, 'path_info', None) or getattr(request, 'path', '') or '').strip()
        return path in REFRESH_RATE_LIMITED_PATHS

    def __call__(self, request: HttpRequest):
        method = (getattr(request, 'method', '') or '').upper()

        if method == 'POST':
            if self.is_login_endpoint(request):
                client_ip = self.get_client_ip(request)
                locked_out, ip_wait_seconds = self.is_ip_locked_out(client_ip)
                if locked_out:
                    response = JsonResponse(
                        {
                            'detail': (
                                f'Request was throttled. Expected available in '
                                f'{ip_wait_seconds} seconds.'
                            )
                        },
                        status=status.HTTP_429_TOO_MANY_REQUESTS,
                    )
                    response['Retry-After'] = str(max(int(ip_wait_seconds), 1))
                    return response

                from apps.orders.auth import check_and_record_login_rate_limit

                allowed, wait_seconds = check_and_record_login_rate_limit(request)
                if not allowed:
                    response = JsonResponse(
                        {
                            'detail': (
                                f'Request was throttled. Expected available in '
                                f'{wait_seconds} seconds.'
                            )
                        },
                        status=status.HTTP_429_TOO_MANY_REQUESTS,
                    )
                    response['Retry-After'] = str(max(int(wait_seconds), 1))
                    return response

                response = self.get_response(request)
                status_code = getattr(response, 'status_code', 200)
                if status_code in (
                    status.HTTP_400_BAD_REQUEST,
                    status.HTTP_401_UNAUTHORIZED,
                    status.HTTP_403_FORBIDDEN,
                ):
                    record_failed_login_attempt(request)
                elif 200 <= status_code < 300 and not getattr(
                    request, '_auth_failed_login_cleared', False
                ):
                    clear_failed_login_attempts(request)
                return response

            if self.is_refresh_endpoint(request):
                from apps.orders.auth import check_and_record_refresh_rate_limit

                allowed, wait_seconds = check_and_record_refresh_rate_limit(request)
                if not allowed:
                    response = JsonResponse(
                        {
                            'detail': (
                                f'Request was throttled. Expected available in '
                                f'{wait_seconds} seconds.'
                            )
                        },
                        status=status.HTTP_429_TOO_MANY_REQUESTS,
                    )
                    response['Retry-After'] = str(max(int(wait_seconds), 1))
                    return response

        return self.get_response(request)


FailedLoginAttemptMiddleware = AuthRateLimitMiddleware

__all__ = [
    'AuthRateLimitMiddleware',
    'CACHE_PREFIX_RL_LOGIN_FAIL_IP',
    'FailedLoginAttemptMiddleware',
    'LOGIN_ENDPOINT_URL_NAMES',
    'LOGIN_RATE_LIMITED_PATHS',
    'REFRESH_RATE_LIMITED_PATHS',
    'clear_failed_login_attempts',
    'extract_client_ip',
    'get_client_ip_failed_login_count',
    'record_failed_login_attempt',
]
