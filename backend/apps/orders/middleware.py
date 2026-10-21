"""
Custom Django Middleware module (`apps.orders.middleware`) using the Django cache framework
(`django.core.cache`) to track login attempts by client IP address and enforce
`AUTH_MAX_FAILED_LOGIN_ATTEMPTS` and `AUTH_FAILED_LOGIN_WINDOW_SECONDS` across all login endpoints.
"""

from apps.orders.auth import (
    AuthRateLimitMiddleware,
    FailedLoginAttemptMiddleware,
    clear_failed_login_attempts,
    get_client_ip_failed_login_count,
    record_failed_login_attempt,
)

__all__ = [
    'AuthRateLimitMiddleware',
    'FailedLoginAttemptMiddleware',
    'clear_failed_login_attempts',
    'get_client_ip_failed_login_count',
    'record_failed_login_attempt',
]
