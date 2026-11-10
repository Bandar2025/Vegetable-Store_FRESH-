"""
Django system checks (`core.checks`) for strict production validation of SECRET_KEY and ALLOWED_HOSTS when DEBUG is False.
"""

from __future__ import annotations

from django.conf import settings
from django.core.checks import Error, Tags, register

FORBIDDEN_PRODUCTION_SECRET_KEYS = frozenset({
    '',
    'django-insecure-dummy-key',
    'local-dev-only-insecure-key-do-not-use-in-production-0123456789abcdef',
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


@register(Tags.security)
def validate_production_security_settings(app_configs, **kwargs) -> list[Error]:
    """
    Enforces strict validation of SECRET_KEY and ALLOWED_HOSTS when DEBUG is False,
    using Django's system check framework.
    """
    errors: list[Error] = []
    debug = getattr(settings, 'DEBUG', True)

    if not debug:
        secret_key = getattr(settings, 'SECRET_KEY', '')
        if not isinstance(secret_key, str) or not secret_key.strip():
            errors.append(
                Error(
                    "SECRET_KEY is missing or empty.",
                    hint="A secure SECRET_KEY must be explicitly set in production when DEBUG is False.",
                    id="fresh.E001",
                )
            )
        else:
            cleaned = secret_key.strip()
            lowered = cleaned.lower()
            if cleaned in FORBIDDEN_PRODUCTION_SECRET_KEYS or lowered in FORBIDDEN_PRODUCTION_SECRET_KEYS:
                errors.append(
                    Error(
                        "SECRET_KEY uses an insecure or dummy production value.",
                        hint="Do not use default or placeholder secret keys in production.",
                        id="fresh.E002",
                    )
                )
            else:
                for sub in FORBIDDEN_PRODUCTION_SECRET_SUBSTRINGS:
                    if sub in lowered:
                        errors.append(
                            Error(
                                f"SECRET_KEY contains forbidden placeholder pattern ({sub!r}).",
                                hint="Use a high-entropy cryptographically secure secret key in production.",
                                id="fresh.E003",
                            )
                        )
                        break
            if len(cleaned) < 50:
                errors.append(
                    Error(
                        f"SECRET_KEY is too short (got {len(cleaned)} characters; minimum is 50).",
                        hint="Production SECRET_KEY must be at least 50 characters long.",
                        id="fresh.E004",
                    )
                )
            if len(set(cleaned)) < 10:
                errors.append(
                    Error(
                        f"SECRET_KEY has insufficient character diversity (got {len(set(cleaned))} unique chars; minimum is 10).",
                        hint="Production SECRET_KEY must contain at least 10 distinct characters.",
                        id="fresh.E005",
                    )
                )

        allowed_hosts = getattr(settings, 'ALLOWED_HOSTS', [])
        if not isinstance(allowed_hosts, (list, tuple)) or not allowed_hosts or not [h for h in allowed_hosts if str(h).strip()]:
            errors.append(
                Error(
                    "ALLOWED_HOSTS is missing or empty.",
                    hint="ALLOWED_HOSTS must be explicitly configured with valid production hostnames when DEBUG is False.",
                    id="fresh.E006",
                )
            )
        else:
            if '*' in allowed_hosts:
                errors.append(
                    Error(
                        "Wildcard '*' in ALLOWED_HOSTS is not permitted when DEBUG is False.",
                        hint="Specify explicit production domain names in ALLOWED_HOSTS.",
                        id="fresh.E007",
                    )
                )

    return errors
