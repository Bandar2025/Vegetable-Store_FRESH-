"""
Django middleware package for the FRESH backend.
"""

from middleware.auth_rate_limit import (
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
