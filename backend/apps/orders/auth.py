"""
Server-Side Authentication, JWT Token Lifecycle (Allowlist/Blocklist Rotation & Revocation),
Cache-Based Login/Refresh Rate Limiting (Middleware, DRF Decorator & Throttles),
Identity Mapping, and Zero-Trust Authorization Resolution.

Guarantees:
- Passwords are never stored or accepted in plaintext; Django's cryptographic password hashers
  (PBKDF2 by default) are strictly enforced via `assert_password_hashed()`.
- Authentication tokens use signed HMAC-SHA256 (`HS256`) JWTs with a server-enforced
  allowlist/blocklist lifecycle:
  - Raw access and refresh JWTs contain cryptographic entropy (`jti`, `pair_jti`, `family_id`)
    and explicit `iat`/`exp` claims, and are never stored in plaintext in the database
    (only SHA-256 digests are persisted in `AuthSessionToken`).
  - Active token pairs are registered in a cache- and database-backed **Allowlist**.
  - Access tokens expire after `AUTH_ACCESS_TOKEN_TTL_SECONDS` (default 900s / 15m).
  - Refresh tokens expire after `AUTH_REFRESH_TOKEN_TTL_SECONDS` (default 604800s / 7d) and
    enforce single-use rotation (`rotate_session_token`).
  - Once a refresh token is rotated or a logout occurs (`revoke_token_session` /
    `revoke_all_user_tokens`), the prior refresh token, its associated access token(s),
    and associated session data are immediately added to the **Blocklist**, removed from the
    **Allowlist**, and invalidated in both cache and database.
  - Replaying an already-rotated refresh token immediately blocklists and revokes the entire
    `token_family_id` (`REUSE_DETECTED`) and invalidates user session data.
- Login and token-renewal endpoints enforce cache-based rate limiting and repeated failed
  login attempt lockout (`AUTH_MAX_FAILED_LOGIN_ATTEMPTS` and `AUTH_LOGIN_RATE_LIMIT_REQUESTS`)
  tracked by IP (`ip:<client_ip>`) and User Identifier (`user:<normalized_username>`) via
  `AuthRateLimitMiddleware`, `@enforce_auth_rate_limit`, `LoginRateThrottle`, and
  `TokenRefreshRateThrottle`.
- Authentication failures never reveal whether an account exists, whether a password was wrong,
  or whether an account/profile is inactive.
- Client-supplied `role`, `customer_id`, `store_id`, `branch_id`, or `actor_id` values are never
  trusted as proof of authorization.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone as dt_timezone
from functools import wraps
import hashlib
import hmac
import json
import math
import secrets
from typing import Callable, Iterable, Sequence

from django.conf import settings
from django.contrib.auth import get_user_model, logout as django_logout
from django.contrib.auth.hashers import identify_hasher
from django.core.cache import cache
from django.db import transaction
from django.db.models.signals import post_delete
from django.dispatch import receiver
from django.http import HttpRequest, JsonResponse
from django.utils import timezone
from rest_framework import exceptions, status
from rest_framework.authentication import TokenAuthentication, get_authorization_header
from rest_framework.authtoken.models import Token
from rest_framework.response import Response
from rest_framework.throttling import BaseThrottle

from apps.orders.models import (
    AuthSessionToken,
    AuthThrottleEvent,
    BranchRoleAssignment,
    UserIdentityProfile,
)
from apps.orders.services import (
    ActorRole,
    AuthenticationBoundaryError,
    ServiceActor,
)
from apps.products.models import Branch, Store


GENERIC_INVALID_CREDENTIALS_MESSAGE = 'Invalid authentication credentials.'
GENERIC_INVALID_ACCESS_TOKEN_MESSAGE = 'Invalid or expired authentication token.'
GENERIC_INVALID_REFRESH_TOKEN_MESSAGE = 'Invalid or expired refresh token.'

# Cache key prefixes for JWT allowlist/blocklist and rate limiting
CACHE_PREFIX_ALLOW_TOKEN = 'auth:jwt:allowlist:token:'
CACHE_PREFIX_ALLOW_JTI = 'auth:jwt:allowlist:jti:'
CACHE_PREFIX_ALLOW_FAMILY = 'auth:jwt:allowlist:family:'
CACHE_PREFIX_BLOCK_TOKEN = 'auth:jwt:blocklist:token:'
CACHE_PREFIX_BLOCK_JTI = 'auth:jwt:blocklist:jti:'
CACHE_PREFIX_BLOCK_FAMILY = 'auth:jwt:blocklist:family:'
CACHE_PREFIX_USER_SESSION = 'auth:session:user:'

CACHE_PREFIX_RL_LOGIN_REQ_IP = 'auth:ratelimit:login_req:ip:'
CACHE_PREFIX_RL_LOGIN_REQ_USER = 'auth:ratelimit:login_req:user:'
CACHE_PREFIX_RL_LOGIN_FAIL_IP = 'auth:ratelimit:login_fail:ip:'
CACHE_PREFIX_RL_LOGIN_FAIL_USER = 'auth:ratelimit:login_fail:user:'
CACHE_PREFIX_RL_REFRESH_REQ_IP = 'auth:ratelimit:refresh_req:ip:'
CACHE_KEY_RL_TRACKED_KEYS = 'auth:ratelimit:tracked_keys'

LOGIN_RATE_LIMITED_PATHS = frozenset({
    '/api/v1/auth/login',
    '/api/v1/auth/login/',
    '/api/v1/auth/token',
    '/api/v1/auth/token/',
})
REFRESH_RATE_LIMITED_PATHS = frozenset({
    '/api/v1/auth/refresh',
    '/api/v1/auth/refresh/',
    '/api/v1/auth/token/refresh',
    '/api/v1/auth/token/refresh/',
    '/api/v1/auth/renew',
    '/api/v1/auth/renew/',
})


def hash_opaque_token(raw_token: str) -> str:
    """
    Computes the deterministic SHA-256 hex digest of a JWT or opaque bearer/refresh token.
    Raw tokens are never persisted in plaintext in the database.
    """
    if not isinstance(raw_token, str) or not raw_token.strip():
        raise ValueError("raw_token must be a non-empty string.")
    return hashlib.sha256(raw_token.strip().encode('utf-8')).hexdigest()


def get_access_token_ttl_seconds() -> int:
    ttl = int(getattr(settings, 'AUTH_ACCESS_TOKEN_TTL_SECONDS', 900))
    return max(ttl, 1)


def get_refresh_token_ttl_seconds() -> int:
    access_ttl = get_access_token_ttl_seconds()
    refresh_ttl = int(getattr(settings, 'AUTH_REFRESH_TOKEN_TTL_SECONDS', 604800))
    return max(refresh_ttl, access_ttl)


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode('ascii')


def _b64url_decode(segment: str) -> bytes:
    if not isinstance(segment, str) or not segment:
        raise ValueError("Invalid base64url segment.")
    padding = '=' * (-len(segment) % 4)
    return base64.urlsafe_b64decode((segment + padding).encode('ascii'))


def _get_jwt_signing_key() -> bytes:
    signing_key = getattr(settings, 'AUTH_JWT_SIGNING_KEY', None) or getattr(
        settings, 'SECRET_KEY', ''
    )
    if not isinstance(signing_key, str) or not signing_key:
        raise AuthenticationBoundaryError("JWT signing key is not configured.")
    return signing_key.encode('utf-8')


def encode_jwt(payload: dict) -> str:
    """
    Encodes and signs a standards-compliant HS256 JWT (`header.payload.signature`).
    """
    header = {
        'alg': getattr(settings, 'AUTH_JWT_ALGORITHM', 'HS256'),
        'typ': 'JWT',
    }
    if header['alg'] != 'HS256':
        raise AuthenticationBoundaryError(f"Unsupported JWT algorithm: {header['alg']}")

    header_b64 = _b64url_encode(
        json.dumps(header, separators=(',', ':'), sort_keys=True).encode('utf-8')
    )
    payload_b64 = _b64url_encode(
        json.dumps(payload, separators=(',', ':'), sort_keys=True).encode('utf-8')
    )
    signing_input = f"{header_b64}.{payload_b64}".encode('ascii')
    signature = hmac.new(_get_jwt_signing_key(), signing_input, hashlib.sha256).digest()
    signature_b64 = _b64url_encode(signature)
    return f"{header_b64}.{payload_b64}.{signature_b64}"


def decode_and_verify_jwt(
    raw_token: str,
    *,
    expected_type: str | None = None,
    verify_exp: bool = True,
    now: datetime | None = None,
) -> dict:
    """
    Decodes and verifies an HS256 JWT signature, structure, token type, and expiration.
    Raises `AuthenticationBoundaryError` on any tampering, malformed segment, or expiry.
    """
    if not isinstance(raw_token, str) or not raw_token.strip():
        raise AuthenticationBoundaryError("Empty JWT token.")

    parts = raw_token.strip().split('.')
    if len(parts) != 3:
        raise AuthenticationBoundaryError("Malformed JWT token structure.")

    header_b64, payload_b64, signature_b64 = parts
    signing_input = f"{header_b64}.{payload_b64}".encode('ascii')
    expected_sig = hmac.new(_get_jwt_signing_key(), signing_input, hashlib.sha256).digest()

    try:
        provided_sig = _b64url_decode(signature_b64)
        if not hmac.compare_digest(provided_sig, expected_sig):
            raise AuthenticationBoundaryError("JWT signature verification failed.")
        header = json.loads(_b64url_decode(header_b64).decode('utf-8'))
        payload = json.loads(_b64url_decode(payload_b64).decode('utf-8'))
    except AuthenticationBoundaryError:
        raise
    except Exception as exc:
        raise AuthenticationBoundaryError("Invalid JWT encoding or claims.") from exc

    if not isinstance(header, dict) or header.get('alg') != 'HS256' or header.get('typ') != 'JWT':
        raise AuthenticationBoundaryError("Invalid JWT header.")
    if not isinstance(payload, dict):
        raise AuthenticationBoundaryError("Invalid JWT payload.")

    if expected_type is not None and payload.get('type') != expected_type:
        raise AuthenticationBoundaryError(
            f"Invalid JWT token type: expected '{expected_type}'."
        )

    if verify_exp:
        exp = payload.get('exp')
        if not isinstance(exp, (int, float)):
            raise AuthenticationBoundaryError("JWT missing valid exp claim.")
        effective_now = now or timezone.now()
        if effective_now.timestamp() >= float(exp):
            raise AuthenticationBoundaryError("JWT has expired.")

    return payload


def _extract_jwt_claims_unverified_exp(raw_token: str) -> dict | None:
    """
    Verifies the JWT signature and returns its claims even if already expired, or None if not a JWT.
    """
    if not isinstance(raw_token, str) or raw_token.strip().count('.') != 2:
        return None
    try:
        return decode_and_verify_jwt(raw_token, verify_exp=False)
    except AuthenticationBoundaryError:
        return None


def allowlist_token_pair(
    session_token: AuthSessionToken,
    *,
    raw_access_token: str | None = None,
    raw_refresh_token: str | None = None,
    access_jti: str | None = None,
    refresh_jti: str | None = None,
    access_ttl: int | None = None,
    refresh_ttl: int | None = None,
) -> None:
    """
    Registers a newly issued access + refresh token pair in the server-side cache allowlist
    and binds the active pair to `session_token.token_family_id`.
    """
    eff_access_ttl = access_ttl or get_access_token_ttl_seconds()
    eff_refresh_ttl = refresh_ttl or get_refresh_token_ttl_seconds()

    cache.set(
        f"{CACHE_PREFIX_ALLOW_TOKEN}{session_token.access_token_hash}",
        session_token.token_family_id,
        timeout=eff_access_ttl,
    )
    cache.set(
        f"{CACHE_PREFIX_ALLOW_TOKEN}{session_token.refresh_token_hash}",
        session_token.token_family_id,
        timeout=eff_refresh_ttl,
    )
    cache.delete(f"{CACHE_PREFIX_BLOCK_TOKEN}{session_token.access_token_hash}")
    cache.delete(f"{CACHE_PREFIX_BLOCK_TOKEN}{session_token.refresh_token_hash}")

    if access_jti:
        cache.set(f"{CACHE_PREFIX_ALLOW_JTI}{access_jti}", session_token.token_family_id, timeout=eff_access_ttl)
        cache.delete(f"{CACHE_PREFIX_BLOCK_JTI}{access_jti}")
    if refresh_jti:
        cache.set(f"{CACHE_PREFIX_ALLOW_JTI}{refresh_jti}", session_token.token_family_id, timeout=eff_refresh_ttl)
        cache.delete(f"{CACHE_PREFIX_BLOCK_JTI}{refresh_jti}")

    cache.set(
        f"{CACHE_PREFIX_ALLOW_FAMILY}{session_token.token_family_id}",
        {
            'session_token_id': session_token.pk,
            'access_token_hash': session_token.access_token_hash,
            'refresh_token_hash': session_token.refresh_token_hash,
            'access_jti': access_jti,
            'refresh_jti': refresh_jti,
        },
        timeout=eff_refresh_ttl,
    )
    cache.set(
        f"{CACHE_PREFIX_USER_SESSION}{session_token.user_id}",
        {
            'active': True,
            'token_family_id': session_token.token_family_id,
            'session_token_id': session_token.pk,
        },
        timeout=eff_refresh_ttl,
    )


def blocklist_token_hashes(
    *,
    access_token_hashes: Iterable[str] = (),
    refresh_token_hashes: Iterable[str] = (),
    jtis: Iterable[str] = (),
    reason: str = AuthSessionToken.RevocationReason.REVOKED,
    ttl_seconds: int | None = None,
) -> None:
    """
    Immediately adds the given access token hashes, refresh token hashes, and JTIs to the
    cache blocklist and removes them from the cache allowlist.
    """
    eff_ttl = ttl_seconds or get_refresh_token_ttl_seconds()
    for a_hash in access_token_hashes:
        if a_hash:
            cache.set(f"{CACHE_PREFIX_BLOCK_TOKEN}{a_hash}", reason, timeout=eff_ttl)
            cache.delete(f"{CACHE_PREFIX_ALLOW_TOKEN}{a_hash}")
    for r_hash in refresh_token_hashes:
        if r_hash:
            cache.set(f"{CACHE_PREFIX_BLOCK_TOKEN}{r_hash}", reason, timeout=eff_ttl)
            cache.delete(f"{CACHE_PREFIX_ALLOW_TOKEN}{r_hash}")
    for jti in jtis:
        if jti:
            cache.set(f"{CACHE_PREFIX_BLOCK_JTI}{jti}", reason, timeout=eff_ttl)
            cache.delete(f"{CACHE_PREFIX_ALLOW_JTI}{jti}")


def blocklist_token_family(
    token_family_id: str,
    *,
    reason: str = AuthSessionToken.RevocationReason.REVOKED,
    ttl_seconds: int | None = None,
) -> None:
    """
    Immediately blocklists an entire `token_family_id` and removes its allowlist pointer.
    """
    if not token_family_id:
        return
    eff_ttl = ttl_seconds or get_refresh_token_ttl_seconds()
    family_state = cache.get(f"{CACHE_PREFIX_ALLOW_FAMILY}{token_family_id}")
    if isinstance(family_state, dict):
        blocklist_token_hashes(
            access_token_hashes=[family_state.get('access_token_hash') or ''],
            refresh_token_hashes=[family_state.get('refresh_token_hash') or ''],
            jtis=[
                family_state.get('access_jti') or '',
                family_state.get('refresh_jti') or '',
            ],
            reason=reason,
            ttl_seconds=eff_ttl,
        )
    cache.set(f"{CACHE_PREFIX_BLOCK_FAMILY}{token_family_id}", reason, timeout=eff_ttl)
    cache.delete(f"{CACHE_PREFIX_ALLOW_FAMILY}{token_family_id}")


def is_token_blocklisted(
    raw_token: str | None = None,
    *,
    token_hash: str | None = None,
    jti: str | None = None,
    token_family_id: str | None = None,
) -> bool:
    """
    Returns True if the token digest, JTI, or token family is present in the blocklist
    or revoked in `AuthSessionToken`.
    """
    eff_hash = token_hash
    if eff_hash is None and isinstance(raw_token, str) and raw_token.strip():
        eff_hash = hash_opaque_token(raw_token)

    if eff_hash and cache.get(f"{CACHE_PREFIX_BLOCK_TOKEN}{eff_hash}") is not None:
        return True

    eff_jti = jti
    eff_family = token_family_id
    if raw_token and (eff_jti is None or eff_family is None):
        claims = _extract_jwt_claims_unverified_exp(raw_token)
        if isinstance(claims, dict):
            eff_jti = eff_jti or claims.get('jti')
            eff_family = eff_family or claims.get('family_id')

    if eff_jti and cache.get(f"{CACHE_PREFIX_BLOCK_JTI}{eff_jti}") is not None:
        return True
    if eff_family and cache.get(f"{CACHE_PREFIX_BLOCK_FAMILY}{eff_family}") is not None:
        return True

    if eff_hash:
        from django.db.models import Q

        record = (
            AuthSessionToken.objects.filter(
                Q(access_token_hash=eff_hash) | Q(refresh_token_hash=eff_hash)
            )
            .only('revoked_at', 'token_family_id')
            .first()
        )
        if record is not None and record.revoked_at is not None:
            return True

    return False


def is_token_allowlisted(
    raw_token: str | None = None,
    *,
    token_hash: str | None = None,
    session_token: AuthSessionToken | None = None,
    expected_type: str = 'access',
) -> bool:
    """
    Returns True only if the token is NOT blocklisted, is currently active in the allowlist,
    matches the active token pair for its family, and is unrevoked and unexpired in `AuthSessionToken`.
    """
    eff_hash = token_hash
    if eff_hash is None and isinstance(raw_token, str) and raw_token.strip():
        eff_hash = hash_opaque_token(raw_token)

    if session_token is None and eff_hash:
        if expected_type == 'refresh':
            session_token = AuthSessionToken.objects.filter(refresh_token_hash=eff_hash).first()
        else:
            session_token = AuthSessionToken.objects.filter(access_token_hash=eff_hash).first()

    if session_token is None:
        return False

    eff_hash = eff_hash or (
        session_token.refresh_token_hash
        if expected_type == 'refresh'
        else session_token.access_token_hash
    )

    if is_token_blocklisted(
        raw_token,
        token_hash=eff_hash,
        token_family_id=session_token.token_family_id,
    ):
        return False

    now = timezone.now()
    if session_token.revoked_at is not None:
        return False
    if expected_type == 'refresh' and now >= session_token.refresh_expires_at:
        return False
    if expected_type == 'access' and now >= session_token.access_expires_at:
        return False

    family_state = cache.get(f"{CACHE_PREFIX_ALLOW_FAMILY}{session_token.token_family_id}")
    if isinstance(family_state, dict):
        expected_hash_key = (
            'refresh_token_hash' if expected_type == 'refresh' else 'access_token_hash'
        )
        allowed_hash = family_state.get(expected_hash_key)
        if allowed_hash and allowed_hash != eff_hash:
            return False

    return True


def invalidate_user_session_data(
    user,
    *,
    request=None,
    clear_all_db_sessions: bool = True,
) -> None:
    """
    Immediately invalidates server-side session data associated with `user` and/or `request`:
    1. Clears cached session state for the user (`auth:session:user:<user_id>`).
    2. Flushes `request.session` and logs out any attached Django session on `request`.
    3. Deletes active `django.contrib.sessions.models.Session` rows belonging to `user`.
    """
    user_pk = getattr(user, 'pk', None)
    if user_pk is not None:
        cache.delete(f"{CACHE_PREFIX_USER_SESSION}{user_pk}")

    if request is not None:
        underlying_req = getattr(request, '_request', request)
        session = getattr(underlying_req, 'session', None) or getattr(request, 'session', None)
        if session is not None and hasattr(session, 'flush'):
            try:
                session.flush()
            except Exception:
                pass
        if isinstance(underlying_req, HttpRequest):
            try:
                django_logout(underlying_req)
            except Exception:
                pass

    if clear_all_db_sessions and user_pk is not None:
        try:
            from django.contrib.sessions.models import Session

            target_uid = str(user_pk)
            active_sessions = Session.objects.filter(expire_date__gte=timezone.now())
            session_keys_to_delete = []
            for sess in active_sessions:
                try:
                    decoded = sess.get_decoded()
                except Exception:
                    continue
                if str(decoded.get('_auth_user_id', '')) == target_uid:
                    session_keys_to_delete.append(sess.session_key)
            if session_keys_to_delete:
                Session.objects.filter(session_key__in=session_keys_to_delete).delete()
        except Exception:
            pass


@dataclass(frozen=True)
class IssuedTokenPair:
    session_token: AuthSessionToken
    access_token: str
    refresh_token: str
    token_family_id: str
    access_expires_at: datetime
    refresh_expires_at: datetime
    access_expires_in: int
    refresh_expires_in: int


def issue_session_token_pair(
    user,
    *,
    token_family_id: str | None = None,
    now: datetime | None = None,
) -> IssuedTokenPair:
    """
    Creates a new `AuthSessionToken` record for `user` with cryptographically signed HS256
    access and refresh JWTs (storing only their SHA-256 digests in the database), registers
    the pair in the allowlist, and defines explicit expiries.
    """
    effective_now = now or timezone.now()
    access_ttl = get_access_token_ttl_seconds()
    refresh_ttl = get_refresh_token_ttl_seconds()

    access_expires_at = effective_now + timedelta(seconds=access_ttl)
    refresh_expires_at = effective_now + timedelta(seconds=refresh_ttl)
    family_id = (
        token_family_id.strip()
        if isinstance(token_family_id, str) and token_family_id.strip()
        else secrets.token_hex(16)
    )

    pair_jti = secrets.token_hex(16)
    access_jti = secrets.token_hex(16)
    refresh_jti = secrets.token_hex(16)
    while refresh_jti == access_jti:
        refresh_jti = secrets.token_hex(16)

    iat_ts = int(effective_now.timestamp())
    access_exp_ts = int(access_expires_at.timestamp())
    refresh_exp_ts = int(refresh_expires_at.timestamp())
    issuer = getattr(settings, 'AUTH_JWT_ISSUER', 'fresh-backend')

    raw_access_token = encode_jwt(
        {
            'iss': issuer,
            'sub': str(user.pk),
            'user_id': int(user.pk),
            'jti': access_jti,
            'pair_jti': pair_jti,
            'family_id': family_id,
            'type': 'access',
            'iat': iat_ts,
            'exp': access_exp_ts,
        }
    )
    raw_refresh_token = encode_jwt(
        {
            'iss': issuer,
            'sub': str(user.pk),
            'user_id': int(user.pk),
            'jti': refresh_jti,
            'pair_jti': pair_jti,
            'family_id': family_id,
            'type': 'refresh',
            'iat': iat_ts,
            'exp': refresh_exp_ts,
        }
    )

    access_hash = hash_opaque_token(raw_access_token)
    refresh_hash = hash_opaque_token(raw_refresh_token)

    with transaction.atomic():
        session_token = AuthSessionToken.objects.create(
            user=user,
            token_family_id=family_id,
            access_token_hash=access_hash,
            refresh_token_hash=refresh_hash,
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )

    allowlist_token_pair(
        session_token,
        raw_access_token=raw_access_token,
        raw_refresh_token=raw_refresh_token,
        access_jti=access_jti,
        refresh_jti=refresh_jti,
        access_ttl=access_ttl,
        refresh_ttl=refresh_ttl,
    )

    return IssuedTokenPair(
        session_token=session_token,
        access_token=raw_access_token,
        refresh_token=raw_refresh_token,
        token_family_id=family_id,
        access_expires_at=access_expires_at,
        refresh_expires_at=refresh_expires_at,
        access_expires_in=access_ttl,
        refresh_expires_in=refresh_ttl,
    )


def revoke_all_user_tokens(
    user,
    *,
    reason: str = AuthSessionToken.RevocationReason.REVOKED,
    request=None,
    now: datetime | None = None,
) -> int:
    """
    Immediately blocklists and revokes all active `AuthSessionToken` rows, deletes any legacy
    DRF `Token` rows belonging to `user`, and invalidates associated user session data.
    """
    if user is None or not getattr(user, 'pk', None):
        return 0
    effective_now = now or timezone.now()
    with transaction.atomic():
        active_tokens = list(
            AuthSessionToken.objects.filter(
                user_id=user.pk,
                revoked_at__isnull=True,
            ).only('access_token_hash', 'refresh_token_hash', 'token_family_id')
        )
        updated = AuthSessionToken.objects.filter(
            user_id=user.pk,
            revoked_at__isnull=True,
        ).update(
            revoked_at=effective_now,
            revocation_reason=reason,
        )
        Token.objects.filter(user_id=user.pk).delete()

    blocklist_token_hashes(
        access_token_hashes=[t.access_token_hash for t in active_tokens],
        refresh_token_hashes=[t.refresh_token_hash for t in active_tokens],
        reason=reason,
    )
    for family_id in {t.token_family_id for t in active_tokens}:
        blocklist_token_family(family_id, reason=reason)

    invalidate_user_session_data(user, request=request)
    return updated


def revoke_token_session(
    *,
    user,
    session_token: AuthSessionToken | None = None,
    revoke_all_user_sessions: bool = False,
    reason: str = AuthSessionToken.RevocationReason.LOGOUT,
    request=None,
    now: datetime | None = None,
) -> int:
    """
    Revokes and blocklists the active token session family on logout (or all sessions for `user`
    when `revoke_all_user_sessions=True` or no specific `session_token` is supplied), and
    immediately invalidates associated access tokens and session data.
    """
    effective_now = now or timezone.now()
    with transaction.atomic():
        if (
            isinstance(session_token, AuthSessionToken)
            and not revoke_all_user_sessions
            and session_token.token_family_id
        ):
            target_qs = AuthSessionToken.objects.filter(
                user_id=user.pk,
                token_family_id=session_token.token_family_id,
                revoked_at__isnull=True,
            )
            tokens_to_revoke = list(
                target_qs.only('access_token_hash', 'refresh_token_hash', 'token_family_id')
            )
            updated = target_qs.update(
                revoked_at=effective_now,
                revocation_reason=reason,
            )
            families_to_block = {session_token.token_family_id}
            access_hashes = [session_token.access_token_hash] + [
                t.access_token_hash for t in tokens_to_revoke
            ]
            refresh_hashes = [session_token.refresh_token_hash] + [
                t.refresh_token_hash for t in tokens_to_revoke
            ]
        else:
            target_qs = AuthSessionToken.objects.filter(
                user_id=user.pk,
                revoked_at__isnull=True,
            )
            tokens_to_revoke = list(
                target_qs.only('access_token_hash', 'refresh_token_hash', 'token_family_id')
            )
            updated = target_qs.update(
                revoked_at=effective_now,
                revocation_reason=reason,
            )
            families_to_block = {t.token_family_id for t in tokens_to_revoke}
            access_hashes = [t.access_token_hash for t in tokens_to_revoke]
            refresh_hashes = [t.refresh_token_hash for t in tokens_to_revoke]
        Token.objects.filter(user_id=user.pk).delete()

    blocklist_token_hashes(
        access_token_hashes=access_hashes,
        refresh_token_hashes=refresh_hashes,
        reason=reason,
    )
    for fam_id in families_to_block:
        blocklist_token_family(fam_id, reason=reason)

    invalidate_user_session_data(user, request=request)
    return updated


def rotate_session_token(
    raw_refresh_token: str,
    *,
    request=None,
    now: datetime | None = None,
) -> IssuedTokenPair:
    """
    Atomically validates a refresh JWT, enforces single-use rotation with allowlist/blocklist
    tracking, detects replay of already-rotated refresh tokens (blocklisting and revoking the
    entire token family), verifies that the server-side user/profile/store/branch identity
    remains active, immediately blocklists/invalidates the used refresh token, its associated
    access token, and prior session data, and issues a replacement access + refresh JWT pair.
    """
    if not isinstance(raw_refresh_token, str) or not raw_refresh_token.strip():
        raise AuthenticationBoundaryError(GENERIC_INVALID_REFRESH_TOKEN_MESSAGE)

    effective_now = now or timezone.now()
    cleaned_refresh = raw_refresh_token.strip()

    # Verify cryptographic JWT signature and token type if formatted as a JWT
    jwt_claims: dict | None = None
    jwt_expired = False
    if cleaned_refresh.count('.') == 2:
        jwt_claims = decode_and_verify_jwt(
            cleaned_refresh,
            expected_type='refresh',
            verify_exp=False,
            now=effective_now,
        )
        exp_claim = jwt_claims.get('exp')
        if not isinstance(exp_claim, (int, float)) or effective_now.timestamp() >= float(exp_claim):
            jwt_expired = True

    refresh_hash = hash_opaque_token(cleaned_refresh)
    refresh_jti = jwt_claims.get('jti') if isinstance(jwt_claims, dict) else None

    new_pair: IssuedTokenPair | None = None
    with transaction.atomic():
        current_token = (
            AuthSessionToken.objects.select_for_update()
            .select_related('user')
            .filter(refresh_token_hash=refresh_hash)
            .first()
        )
        if current_token is None:
            raise AuthenticationBoundaryError(GENERIC_INVALID_REFRESH_TOKEN_MESSAGE)

        user = current_token.user

        # 1. Check if already revoked (and enforce refresh token reuse detection)
        if current_token.revoked_at is not None:
            if current_token.revocation_reason == AuthSessionToken.RevocationReason.ROTATED:
                # Compromised / replayed refresh token: revoke and blocklist the entire token family!
                family_tokens = list(
                    AuthSessionToken.objects.filter(
                        token_family_id=current_token.token_family_id,
                    ).only('access_token_hash', 'refresh_token_hash')
                )
                AuthSessionToken.objects.filter(
                    token_family_id=current_token.token_family_id,
                    revoked_at__isnull=True,
                ).update(
                    revoked_at=effective_now,
                    revocation_reason=AuthSessionToken.RevocationReason.REUSE_DETECTED,
                )
                blocklist_token_hashes(
                    access_token_hashes=[t.access_token_hash for t in family_tokens],
                    refresh_token_hashes=[t.refresh_token_hash for t in family_tokens],
                    jtis=[refresh_jti] if refresh_jti else (),
                    reason=AuthSessionToken.RevocationReason.REUSE_DETECTED,
                )
                blocklist_token_family(
                    current_token.token_family_id,
                    reason=AuthSessionToken.RevocationReason.REUSE_DETECTED,
                )
                invalidate_user_session_data(user, request=request)
            else:
                blocklist_token_hashes(
                    access_token_hashes=[current_token.access_token_hash],
                    refresh_token_hashes=[current_token.refresh_token_hash],
                    jtis=[refresh_jti] if refresh_jti else (),
                    reason=current_token.revocation_reason or AuthSessionToken.RevocationReason.REVOKED,
                )
        # 2. Check if blocklisted in cache
        elif is_token_blocklisted(
            cleaned_refresh,
            token_hash=refresh_hash,
            jti=refresh_jti,
            token_family_id=current_token.token_family_id,
        ):
            current_token.revoked_at = effective_now
            current_token.revocation_reason = AuthSessionToken.RevocationReason.REVOKED
            current_token.save(update_fields=['revoked_at', 'revocation_reason'])
            invalidate_user_session_data(user, request=request)
        # 3. Check refresh token expiry (DB or JWT claim)
        elif jwt_expired or effective_now >= current_token.refresh_expires_at:
            current_token.revoked_at = effective_now
            current_token.revocation_reason = AuthSessionToken.RevocationReason.EXPIRED
            current_token.save(update_fields=['revoked_at', 'revocation_reason'])
            blocklist_token_hashes(
                access_token_hashes=[current_token.access_token_hash],
                refresh_token_hashes=[current_token.refresh_token_hash],
                jtis=[refresh_jti] if refresh_jti else (),
                reason=AuthSessionToken.RevocationReason.EXPIRED,
            )
            invalidate_user_session_data(user, request=request)
        else:
            # 4. Verify server-side user, profile, store, and branch authorization remain active
            try:
                resolve_service_actor_from_user(user)
            except AuthenticationBoundaryError:
                revoke_all_user_tokens(
                    user,
                    reason=AuthSessionToken.RevocationReason.ACCOUNT_INACTIVE,
                    request=request,
                    now=effective_now,
                )
            else:
                # 5. Collect and immediately blocklist/invalidate the current token pair and
                # any other unrevoked tokens in the same family, plus prior session data
                prior_family_tokens = list(
                    AuthSessionToken.objects.filter(
                        token_family_id=current_token.token_family_id,
                        revoked_at__isnull=True,
                    ).only('id', 'access_token_hash', 'refresh_token_hash')
                )
                family_state = cache.get(f"{CACHE_PREFIX_ALLOW_FAMILY}{current_token.token_family_id}")
                old_jtis = [refresh_jti] if refresh_jti else []
                if isinstance(family_state, dict):
                    if family_state.get('access_jti'):
                        old_jtis.append(family_state['access_jti'])
                    if family_state.get('refresh_jti'):
                        old_jtis.append(family_state['refresh_jti'])

                blocklist_token_hashes(
                    access_token_hashes=[t.access_token_hash for t in prior_family_tokens]
                    + [current_token.access_token_hash],
                    refresh_token_hashes=[t.refresh_token_hash for t in prior_family_tokens]
                    + [current_token.refresh_token_hash],
                    jtis=old_jtis,
                    reason=AuthSessionToken.RevocationReason.ROTATED,
                )
                invalidate_user_session_data(user, request=request)

                # Mark any sibling unrevoked tokens in the family as ROTATED before issuing the new pair
                AuthSessionToken.objects.filter(
                    token_family_id=current_token.token_family_id,
                    revoked_at__isnull=True,
                ).exclude(pk=current_token.pk).update(
                    revoked_at=effective_now,
                    revocation_reason=AuthSessionToken.RevocationReason.ROTATED,
                )

                # 6. Issue new JWT pair in the same token_family_id and register in allowlist
                new_pair = issue_session_token_pair(
                    user,
                    token_family_id=current_token.token_family_id,
                    now=effective_now,
                )
                current_token.revoked_at = effective_now
                current_token.revocation_reason = AuthSessionToken.RevocationReason.ROTATED
                current_token.replaced_by = new_pair.session_token
                current_token.last_used_at = effective_now
                current_token.save(
                    update_fields=['revoked_at', 'revocation_reason', 'replaced_by', 'last_used_at']
                )

    if new_pair is None:
        raise AuthenticationBoundaryError(GENERIC_INVALID_REFRESH_TOKEN_MESSAGE)

    return new_pair


class ExpiringTokenAuthentication(TokenAuthentication):
    """
    DRF-compatible JWT and token authentication backend that enforces:
    - Cryptographic HS256 JWT verification (`type == 'access'`, signature, `exp` claim).
    - Immediate rejection of blocklisted tokens, JTIs, or token families.
    - Verification against the server-side allowlist and `AuthSessionToken.access_token_hash`.
    - Immediate rejection of revoked tokens (`revoked_at IS NOT NULL`).
    - Immediate rejection of expired access tokens (`now >= access_expires_at`).
    - Immediate rejection of inactive users (`not user.is_active`) or unhashed passwords.
    - Support for both `Authorization: Token <token>` and `Authorization: Bearer <token>`.
    - Safe fallback expiry check for any legacy `rest_framework.authtoken.models.Token`.
    """

    keyword = 'Token'
    supported_keywords = ('token', 'bearer')

    def authenticate(self, request):
        auth = get_authorization_header(request).split()
        if not auth or auth[0].lower().decode('latin-1', errors='ignore') not in self.supported_keywords:
            return None

        if len(auth) == 1:
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)
        if len(auth) > 2:
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        try:
            raw_token = auth[1].decode('utf-8')
        except UnicodeError as exc:
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE) from exc

        return self.authenticate_credentials(raw_token)

    def authenticate_credentials(self, key: str):
        if not isinstance(key, str) or not key.strip():
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        cleaned_key = key.strip()
        now = timezone.now()
        token_hash = hash_opaque_token(cleaned_key)

        # 1. If the token is a JWT, verify signature and access token claims first
        jwt_claims: dict | None = None
        jwt_expired = False
        if cleaned_key.count('.') == 2:
            try:
                jwt_claims = decode_and_verify_jwt(
                    cleaned_key,
                    expected_type='access',
                    verify_exp=False,
                    now=now,
                )
            except AuthenticationBoundaryError as exc:
                raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE) from exc

            exp_claim = jwt_claims.get('exp')
            if not isinstance(exp_claim, (int, float)) or now.timestamp() >= float(exp_claim):
                jwt_expired = True

        # 2. Check cache blocklist
        if is_token_blocklisted(
            cleaned_key,
            token_hash=token_hash,
            jti=jwt_claims.get('jti') if isinstance(jwt_claims, dict) else None,
            token_family_id=jwt_claims.get('family_id') if isinstance(jwt_claims, dict) else None,
        ):
            AuthSessionToken.objects.filter(
                access_token_hash=token_hash,
                revoked_at__isnull=True,
            ).update(
                revoked_at=now,
                revocation_reason=AuthSessionToken.RevocationReason.REVOKED,
            )
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        session_token = (
            AuthSessionToken.objects.select_related('user')
            .filter(access_token_hash=token_hash)
            .first()
        )
        if session_token is not None:
            if session_token.revoked_at is not None:
                blocklist_token_hashes(
                    access_token_hashes=[session_token.access_token_hash],
                    refresh_token_hashes=[session_token.refresh_token_hash],
                    reason=session_token.revocation_reason or AuthSessionToken.RevocationReason.REVOKED,
                )
                raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

            if jwt_expired or now >= session_token.access_expires_at:
                AuthSessionToken.objects.filter(
                    pk=session_token.pk,
                    revoked_at__isnull=True,
                ).update(
                    revoked_at=now,
                    revocation_reason=AuthSessionToken.RevocationReason.EXPIRED,
                )
                blocklist_token_hashes(
                    access_token_hashes=[session_token.access_token_hash],
                    jtis=[jwt_claims.get('jti')] if isinstance(jwt_claims, dict) and jwt_claims.get('jti') else (),
                    reason=AuthSessionToken.RevocationReason.EXPIRED,
                )
                raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

            if not is_token_allowlisted(
                cleaned_key,
                token_hash=token_hash,
                session_token=session_token,
                expected_type='access',
            ):
                raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

            user = session_token.user
            if user is None or not user.is_active:
                raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)
            try:
                assert_password_hashed(user)
            except AuthenticationBoundaryError as exc:
                raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE) from exc

            AuthSessionToken.objects.filter(pk=session_token.pk).update(last_used_at=now)
            return (user, session_token)

        if jwt_claims is not None:
            # A signed JWT whose session record does not exist is never valid
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        # Legacy fallback for `rest_framework.authtoken.models.Token` with strict TTL enforcement
        legacy_token = Token.objects.select_related('user').filter(key=cleaned_key).first()
        if legacy_token is None:
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        ttl_seconds = get_access_token_ttl_seconds()
        if now >= legacy_token.created + timedelta(seconds=ttl_seconds):
            legacy_token.delete()
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        if not legacy_token.user.is_active:
            legacy_token.delete()
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE)

        try:
            assert_password_hashed(legacy_token.user)
        except AuthenticationBoundaryError as exc:
            raise exceptions.AuthenticationFailed(GENERIC_INVALID_ACCESS_TOKEN_MESSAGE) from exc

        return (legacy_token.user, legacy_token)


def _extract_client_ip(request) -> str:
    meta = getattr(request, 'META', None)
    if not isinstance(meta, dict):
        underlying = getattr(request, '_request', None)
        meta = getattr(underlying, 'META', {}) if underlying is not None else {}
    xff = meta.get('HTTP_X_FORWARDED_FOR')
    if isinstance(xff, str) and xff.strip():
        return xff.split(',')[0].strip()[:100]
    remote_addr = meta.get('REMOTE_ADDR')
    if isinstance(remote_addr, str) and remote_addr.strip():
        return remote_addr.strip()[:100]
    return 'unknown'


def _extract_normalized_username(request) -> str:
    # 1. Check DRF parsed request.data if already available
    data = getattr(request, 'data', None)
    if isinstance(data, dict):
        raw_username = data.get('username')
        if isinstance(raw_username, str) and raw_username.strip():
            return raw_username.strip().lower()[:150]

    # 2. Check Django HttpRequest POST or raw JSON body (for Middleware execution)
    underlying = getattr(request, '_request', request)
    post_data = getattr(underlying, 'POST', None)
    if isinstance(post_data, dict) or hasattr(post_data, 'get'):
        try:
            raw_username = post_data.get('username')
            if isinstance(raw_username, str) and raw_username.strip():
                return raw_username.strip().lower()[:150]
        except Exception:
            pass

    try:
        raw_body = getattr(underlying, 'body', b'')
        if isinstance(raw_body, (bytes, bytearray)) and raw_body:
            decoded_body = raw_body.decode('utf-8', errors='ignore').strip()
            if decoded_body.startswith('{'):
                parsed = json.loads(decoded_body)
                if isinstance(parsed, dict):
                    raw_username = parsed.get('username')
                    if isinstance(raw_username, str) and raw_username.strip():
                        return raw_username.strip().lower()[:150]
    except Exception:
        pass

    return ''


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


def clear_rate_limit_cache() -> None:
    """
    Clears all tracked rate-limiting sliding-window keys from `django.core.cache`.
    """
    tracked = cache.get(CACHE_KEY_RL_TRACKED_KEYS)
    if isinstance(tracked, set) and tracked:
        cache.delete_many(list(tracked))
    cache.delete(CACHE_KEY_RL_TRACKED_KEYS)


@receiver(post_delete, sender=AuthThrottleEvent)
def _sync_cache_on_throttle_event_delete(sender, instance, **kwargs) -> None:
    """
    Synchronizes cache-backed rate-limit buckets when `AuthThrottleEvent` rows are deleted
    (e.g., during administrative reset or test cleanup).
    """
    ident = getattr(instance, 'identifier', '') or ''
    scope = getattr(instance, 'scope', '') or ''
    if ident.startswith('ip:'):
        ip_val = ident[3:]
        if scope == AuthThrottleEvent.Scope.LOGIN_FAILURE:
            cache.delete(f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{ip_val}")
        elif scope == AuthThrottleEvent.Scope.LOGIN_REQUEST:
            cache.delete(f"{CACHE_PREFIX_RL_LOGIN_REQ_IP}{ip_val}")
        elif scope == AuthThrottleEvent.Scope.TOKEN_REFRESH_REQUEST:
            cache.delete(f"{CACHE_PREFIX_RL_REFRESH_REQ_IP}{ip_val}")
    elif ident.startswith('user:'):
        user_val = ident[5:]
        if scope == AuthThrottleEvent.Scope.LOGIN_FAILURE:
            cache.delete(f"{CACHE_PREFIX_RL_LOGIN_FAIL_USER}{user_val}")
        elif scope == AuthThrottleEvent.Scope.LOGIN_REQUEST:
            cache.delete(f"{CACHE_PREFIX_RL_LOGIN_REQ_USER}{user_val}")


def _get_cache_sliding_window_timestamps(
    cache_key: str,
    window_seconds: int,
    now_ts: float,
) -> list[float]:
    raw_list = cache.get(cache_key)
    if not isinstance(raw_list, list):
        return []
    cutoff_ts = now_ts - float(window_seconds)
    pruned = [float(ts) for ts in raw_list if isinstance(ts, (int, float)) and float(ts) >= cutoff_ts]
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
    Returns the current number of failed login attempts recorded in `django.core.cache`
    for `client_ip` within the active `AUTH_FAILED_LOGIN_WINDOW_SECONDS` window.
    """
    if not isinstance(client_ip, str) or not client_ip.strip():
        return 0
    now_ts = timezone.now().timestamp()
    failed_window = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)
    cache_key = f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip.strip()[:100]}"
    return len(_get_cache_sliding_window_timestamps(cache_key, failed_window, now_ts))


def record_failed_login_attempt(request, username: str | None = None) -> None:
    """
    Records a failed login attempt by client IP and (when provided) normalized username
    in `django.core.cache` (primary sliding-window store) and `AuthThrottleEvent`
    for brute-force and credential-stuffing lockout enforcement.
    Idempotent per HTTP request lifecycle so view + middleware do not double-count.
    """
    underlying_req = getattr(request, '_request', request)
    if underlying_req is not None and getattr(underlying_req, '_auth_failed_login_recorded', False):
        return

    now = timezone.now()
    now_ts = now.timestamp()
    failed_window = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)

    client_ip = _extract_client_ip(request)
    normalized_user = (
        username.strip().lower()[:150]
        if isinstance(username, str) and username.strip()
        else _extract_normalized_username(request)
    )

    _append_cache_sliding_window_timestamp(
        f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip}",
        failed_window,
        now_ts,
    )
    events = [
        AuthThrottleEvent(
            scope=AuthThrottleEvent.Scope.LOGIN_FAILURE,
            identifier=f"ip:{client_ip}",
        )
    ]
    if normalized_user:
        _append_cache_sliding_window_timestamp(
            f"{CACHE_PREFIX_RL_LOGIN_FAIL_USER}{normalized_user}",
            failed_window,
            now_ts,
        )
        events.append(
            AuthThrottleEvent(
                scope=AuthThrottleEvent.Scope.LOGIN_FAILURE,
                identifier=f"user:{normalized_user}",
            )
        )
    AuthThrottleEvent.objects.bulk_create(events)

    if underlying_req is not None:
        setattr(underlying_req, '_auth_failed_login_recorded', True)


def clear_failed_login_attempts(request, username: str | None = None) -> None:
    """
    Clears active failed-login counters in `django.core.cache` and `AuthThrottleEvent`
    for the authenticated username and client IP after a valid login before lockout threshold.
    """
    underlying_req = getattr(request, '_request', request)
    client_ip = _extract_client_ip(request)
    normalized_user = (
        username.strip().lower()[:150]
        if isinstance(username, str) and username.strip()
        else _extract_normalized_username(request)
    )
    cache.delete(f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip}")
    identifiers = [f"ip:{client_ip}"]
    if normalized_user:
        cache.delete(f"{CACHE_PREFIX_RL_LOGIN_FAIL_USER}{normalized_user}")
        identifiers.append(f"user:{normalized_user}")
    AuthThrottleEvent.objects.filter(
        scope=AuthThrottleEvent.Scope.LOGIN_FAILURE,
        identifier__in=identifiers,
    ).delete()
    if underlying_req is not None:
        setattr(underlying_req, '_auth_failed_login_cleared', True)


def check_and_record_login_rate_limit(request) -> tuple[bool, int]:
    """
    Cache-based enforcement of `AUTH_MAX_FAILED_LOGIN_ATTEMPTS` (within
    `AUTH_FAILED_LOGIN_WINDOW_SECONDS`) and `AUTH_LOGIN_RATE_LIMIT_REQUESTS` (within
    `AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS`) tracked by client IP and User Identifier.

    Idempotent per HTTP request lifecycle so `AuthRateLimitMiddleware`,
    `@enforce_auth_rate_limit`, and `LoginRateThrottle` cooperate without double-counting.
    Returns `(allowed: bool, retry_after_seconds: int)`.
    """
    underlying_req = getattr(request, '_request', request)
    cached_decision = getattr(underlying_req, '_auth_login_rate_limit_decision', None)
    if cached_decision is not None:
        return cached_decision

    now = timezone.now()
    now_ts = now.timestamp()
    client_ip = _extract_client_ip(request)
    normalized_user = _extract_normalized_username(request)

    max_failed = max(int(getattr(settings, 'AUTH_MAX_FAILED_LOGIN_ATTEMPTS', 5)), 1)
    failed_window = max(int(getattr(settings, 'AUTH_FAILED_LOGIN_WINDOW_SECONDS', 300)), 1)
    max_requests = max(int(getattr(settings, 'AUTH_LOGIN_RATE_LIMIT_REQUESTS', 15)), 1)
    request_window = max(int(getattr(settings, 'AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS', 60)), 1)

    # Prune stale DB audit events outside the active observation windows
    max_retention = max(failed_window, request_window)
    AuthThrottleEvent.objects.filter(
        created_at__lt=now - timedelta(seconds=max_retention)
    ).delete()

    # 1a. Check per-IP failed login lockout via cache
    ip_fail_key = f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}{client_ip}"
    ip_failures = _get_cache_sliding_window_timestamps(ip_fail_key, failed_window, now_ts)
    if len(ip_failures) >= max_failed:
        wait_sec = _compute_retry_after_seconds(ip_failures[0], failed_window, now)
        decision = (False, wait_sec)
        setattr(underlying_req, '_auth_login_rate_limit_decision', decision)
        return decision

    # 1b. Check per-user failed login lockout via cache
    if normalized_user:
        user_fail_key = f"{CACHE_PREFIX_RL_LOGIN_FAIL_USER}{normalized_user}"
        user_failures = _get_cache_sliding_window_timestamps(user_fail_key, failed_window, now_ts)
        if len(user_failures) >= max_failed:
            wait_sec = _compute_retry_after_seconds(user_failures[0], failed_window, now)
            decision = (False, wait_sec)
            setattr(underlying_req, '_auth_login_rate_limit_decision', decision)
            return decision

    # 2a. Check per-IP login request rate limit via cache
    ip_req_key = f"{CACHE_PREFIX_RL_LOGIN_REQ_IP}{client_ip}"
    ip_requests = _get_cache_sliding_window_timestamps(ip_req_key, request_window, now_ts)
    if len(ip_requests) >= max_requests:
        wait_sec = _compute_retry_after_seconds(ip_requests[0], request_window, now)
        decision = (False, wait_sec)
        setattr(underlying_req, '_auth_login_rate_limit_decision', decision)
        return decision

    # 2b. Check per-user login request rate limit via cache (when username is provided)
    if normalized_user:
        user_req_key = f"{CACHE_PREFIX_RL_LOGIN_REQ_USER}{normalized_user}"
        user_requests = _get_cache_sliding_window_timestamps(user_req_key, request_window, now_ts)
        if len(user_requests) >= max_requests:
            wait_sec = _compute_retry_after_seconds(user_requests[0], request_window, now)
            decision = (False, wait_sec)
            setattr(underlying_req, '_auth_login_rate_limit_decision', decision)
            return decision

    # Record allowed login request in cache (by IP and User Identifier) and DB
    _append_cache_sliding_window_timestamp(ip_req_key, request_window, now_ts)
    if normalized_user:
        _append_cache_sliding_window_timestamp(
            f"{CACHE_PREFIX_RL_LOGIN_REQ_USER}{normalized_user}",
            request_window,
            now_ts,
        )
    AuthThrottleEvent.objects.create(
        scope=AuthThrottleEvent.Scope.LOGIN_REQUEST,
        identifier=f"ip:{client_ip}",
    )

    decision = (True, 0)
    setattr(underlying_req, '_auth_login_rate_limit_decision', decision)
    return decision


def check_and_record_refresh_rate_limit(request) -> tuple[bool, int]:
    """
    Cache-based enforcement of `AUTH_REFRESH_RATE_LIMIT_REQUESTS` within
    `AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS` tracked by client IP.
    Idempotent per HTTP request lifecycle.
    """
    underlying_req = getattr(request, '_request', request)
    cached_decision = getattr(underlying_req, '_auth_refresh_rate_limit_decision', None)
    if cached_decision is not None:
        return cached_decision

    now = timezone.now()
    now_ts = now.timestamp()
    client_ip = _extract_client_ip(request)

    max_requests = max(int(getattr(settings, 'AUTH_REFRESH_RATE_LIMIT_REQUESTS', 10)), 1)
    request_window = max(int(getattr(settings, 'AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS', 60)), 1)

    cutoff = now - timedelta(seconds=request_window)
    AuthThrottleEvent.objects.filter(
        scope=AuthThrottleEvent.Scope.TOKEN_REFRESH_REQUEST,
        created_at__lt=cutoff,
    ).delete()

    refresh_req_key = f"{CACHE_PREFIX_RL_REFRESH_REQ_IP}{client_ip}"
    recent_requests = _get_cache_sliding_window_timestamps(refresh_req_key, request_window, now_ts)
    if len(recent_requests) >= max_requests:
        wait_sec = _compute_retry_after_seconds(recent_requests[0], request_window, now)
        decision = (False, wait_sec)
        setattr(underlying_req, '_auth_refresh_rate_limit_decision', decision)
        return decision

    _append_cache_sliding_window_timestamp(refresh_req_key, request_window, now_ts)
    AuthThrottleEvent.objects.create(
        scope=AuthThrottleEvent.Scope.TOKEN_REFRESH_REQUEST,
        identifier=f"ip:{client_ip}",
    )

    decision = (True, 0)
    setattr(underlying_req, '_auth_refresh_rate_limit_decision', decision)
    return decision


def enforce_auth_rate_limit(scope: str = 'login') -> Callable:
    """
    DRF-compatible decorator enforcing cache-based rate limits (`AUTH_MAX_FAILED_LOGIN_ATTEMPTS`
    and `AUTH_LOGIN_RATE_LIMIT_REQUESTS` for `scope='login'`, or
    `AUTH_REFRESH_RATE_LIMIT_REQUESTS` for `scope='refresh'`) tracked by IP and User Identifier.
    Supports both DRF APIView methods (`def post(self, request, ...)`) and function views
    (`def view(request, ...)`).
    """
    normalized_scope = scope.strip().lower()
    if normalized_scope not in ('login', 'refresh'):
        raise ValueError("scope must be 'login' or 'refresh'.")

    def decorator(func: Callable) -> Callable:
        @wraps(func)
        def wrapped(*args, **kwargs):
            # Support both bound view methods (self, request, ...) and function views (request, ...)
            if len(args) >= 2 and hasattr(args[1], 'META'):
                request = args[1]
            elif len(args) >= 1 and hasattr(args[0], 'META'):
                request = args[0]
            else:
                request = kwargs.get('request')

            if request is not None:
                if normalized_scope == 'login':
                    allowed, wait_seconds = check_and_record_login_rate_limit(request)
                else:
                    allowed, wait_seconds = check_and_record_refresh_rate_limit(request)

                if not allowed:
                    return Response(
                        {'detail': f'Request was throttled. Expected available in {wait_seconds} seconds.'},
                        status=status.HTTP_429_TOO_MANY_REQUESTS,
                        headers={'Retry-After': str(max(int(wait_seconds), 1))},
                    )

            return func(*args, **kwargs)

        return wrapped

    return decorator


# Alias for explicit login decorator usage
enforce_login_rate_limit = enforce_auth_rate_limit('login')


LOGIN_ENDPOINT_URL_NAMES = frozenset({
    'auth-login',
    'auth-token',
    'login',
})


class AuthRateLimitMiddleware:
    """
    Custom Django middleware using the Django cache framework (`django.core.cache`) to
    both track and enforce `AUTH_MAX_FAILED_LOGIN_ATTEMPTS` and
    `AUTH_FAILED_LOGIN_WINDOW_SECONDS` (as well as `AUTH_LOGIN_RATE_LIMIT_REQUESTS`)
    for all login endpoints based on client IP addresses:
    - Pre-request enforcement: checks the client IP's sliding-window failed-login counter
      in `django.core.cache` (`auth:ratelimit:login_fail:ip:<client_ip>`) against
      `settings.AUTH_MAX_FAILED_LOGIN_ATTEMPTS` within `AUTH_FAILED_LOGIN_WINDOW_SECONDS`.
      If the limit is reached, immediately returns `429 Too Many Requests` with `Retry-After`.
    - Post-response tracking: tracks authentication outcomes on all login endpoints by
      recording failed login attempts (`status_code` in `400`, `401`, `403`) in
      `django.core.cache` per client IP with TTL `AUTH_FAILED_LOGIN_WINDOW_SECONDS`, or
      clearing the client IP's failed-login counter upon a successful `2xx` login response.
    """

    def __init__(self, get_response: Callable):
        self.get_response = get_response

    @staticmethod
    def get_client_ip(request: HttpRequest) -> str:
        return _extract_client_ip(request)

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
        if normalized in ('/api/v1/auth/login', '/api/v1/auth/token'):
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
                        {'detail': f'Request was throttled. Expected available in {ip_wait_seconds} seconds.'},
                        status=status.HTTP_429_TOO_MANY_REQUESTS,
                    )
                    response['Retry-After'] = str(max(int(ip_wait_seconds), 1))
                    return response

                allowed, wait_seconds = check_and_record_login_rate_limit(request)
                if not allowed:
                    response = JsonResponse(
                        {'detail': f'Request was throttled. Expected available in {wait_seconds} seconds.'},
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
                allowed, wait_seconds = check_and_record_refresh_rate_limit(request)
                if not allowed:
                    response = JsonResponse(
                        {'detail': f'Request was throttled. Expected available in {wait_seconds} seconds.'},
                        status=status.HTTP_429_TOO_MANY_REQUESTS,
                    )
                    response['Retry-After'] = str(max(int(wait_seconds), 1))
                    return response

        return self.get_response(request)


# Descriptive alias for IP-based failed login attempt tracking & enforcement middleware
FailedLoginAttemptMiddleware = AuthRateLimitMiddleware


class LoginRateThrottle(BaseThrottle):
    """
    DRF throttle class enforcing two layers of cache-backed rate limiting on
    `/api/v1/auth/login/` and `/api/v1/auth/token/`:
    1. Repeated failed login lockout (`AUTH_MAX_FAILED_LOGIN_ATTEMPTS` within
       `AUTH_FAILED_LOGIN_WINDOW_SECONDS`, per username and per client IP).
    2. Overall login request rate limit (`AUTH_LOGIN_RATE_LIMIT_REQUESTS` within
       `AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS` per client IP and User Identifier).
    """

    def __init__(self):
        self._wait_seconds: int = 60

    def allow_request(self, request, view) -> bool:
        allowed, wait_seconds = check_and_record_login_rate_limit(request)
        if not allowed:
            self._wait_seconds = max(int(wait_seconds), 1)
            return False
        return True

    def wait(self) -> int:
        return self._wait_seconds


class TokenRefreshRateThrottle(BaseThrottle):
    """
    DRF throttle class enforcing cache-backed rate limiting on token renewal/rotation
    endpoints (`/api/v1/auth/refresh/`, `/api/v1/auth/token/refresh/`, `/api/v1/auth/renew/`)
    using `AUTH_REFRESH_RATE_LIMIT_REQUESTS` within `AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS`.
    """

    def __init__(self):
        self._wait_seconds: int = 60

    def allow_request(self, request, view) -> bool:
        allowed, wait_seconds = check_and_record_refresh_rate_limit(request)
        if not allowed:
            self._wait_seconds = max(int(wait_seconds), 1)
            return False
        return True

    def wait(self) -> int:
        return self._wait_seconds


def assert_password_hashed(user) -> None:
    """
    Verifies that a Django User has a usable, cryptographically hashed password
    and never stores a plaintext password.
    """
    if user is None:
        raise AuthenticationBoundaryError("User account is required.")
    raw_password_field = getattr(user, 'password', None)
    if not raw_password_field or not isinstance(raw_password_field, str):
        raise AuthenticationBoundaryError("User account does not have a valid password hash.")
    if not user.has_usable_password():
        raise AuthenticationBoundaryError("User account does not have a usable password.")
    try:
        identify_hasher(raw_password_field)
    except ValueError as exc:
        raise AuthenticationBoundaryError(
            "User account password is not stored with a valid Django cryptographic hasher."
        ) from exc


def create_user_with_identity_profile(
    *,
    username: str,
    password: str,
    role: str = UserIdentityProfile.Role.CUSTOMER,
    customer_id: str | None = None,
    store: Store | None = None,
    branches: Sequence[Branch] | Iterable[Branch] | None = None,
    is_active: bool = True,
    profile_is_active: bool = True,
    email: str = '',
) -> tuple[object, UserIdentityProfile]:
    """
    Safely provisions a Django User with a cryptographically hashed password and
    creates its authoritative server-side `UserIdentityProfile` and optional
    `BranchRoleAssignment` records inside an atomic transaction.
    """
    if not isinstance(username, str) or not username.strip():
        raise ValueError("username must be a non-empty string.")
    if not isinstance(password, str) or not password or not password.strip():
        raise ValueError("password must be a non-empty string and is hashed before storage.")

    UserModel = get_user_model()
    with transaction.atomic():
        user = UserModel.objects.create_user(
            username=username.strip(),
            email=email.strip() if email else '',
            password=password,
            is_active=is_active,
        )
        assert_password_hashed(user)

        effective_customer_id = customer_id
        if role == UserIdentityProfile.Role.CUSTOMER and not effective_customer_id:
            effective_customer_id = f"cust-user-{user.pk}"

        profile = UserIdentityProfile.objects.create(
            user=user,
            role=role,
            customer_id=effective_customer_id.strip() if effective_customer_id else None,
            store=store,
            is_active=profile_is_active,
        )

        if branches:
            for branch in branches:
                BranchRoleAssignment.objects.create(
                    profile=profile,
                    branch=branch,
                    is_active=True,
                )

        return user, profile


def resolve_service_actor_from_user(user) -> ServiceActor:
    """
    Resolves an authoritative `ServiceActor` strictly from the server-side authenticated `user`
    and its `UserIdentityProfile`, `Store`, `Branch`, and `BranchRoleAssignment` records.

    Fails closed (`AuthenticationBoundaryError`) if:
    - `user` is unauthenticated or `not user.is_active`
    - `user.password` is unhashed or unusable
    - `UserIdentityProfile` is missing or `not profile.is_active`
    - Assigned `Store` is missing or `not store.is_active` (for staff/owner or store-bound accounts)
    - No active `Branch` / `BranchRoleAssignment` exists for `BRANCH_MANAGER` or `OWNER`
    """
    if user is None or not getattr(user, 'is_authenticated', False):
        raise AuthenticationBoundaryError("Authentication credentials were not provided or are invalid.")
    if not getattr(user, 'is_active', False):
        raise AuthenticationBoundaryError("User account is inactive.")

    assert_password_hashed(user)

    profile = (
        UserIdentityProfile.objects.select_related('store')
        .filter(user_id=user.pk)
        .first()
    )
    if profile is None:
        raise AuthenticationBoundaryError(
            f"User '{user.username}' does not have an assigned server-side identity profile."
        )
    if not profile.is_active:
        raise AuthenticationBoundaryError(
            f"Identity profile for user '{user.username}' is inactive."
        )

    actor_id = f"user:{user.pk}"

    if profile.role == UserIdentityProfile.Role.CUSTOMER:
        if not profile.customer_id or not profile.customer_id.strip():
            raise AuthenticationBoundaryError(
                f"Customer identity profile for user '{user.username}' is missing customer_id."
            )
        if profile.store_id is not None:
            if profile.store is None or not profile.store.is_active:
                raise AuthenticationBoundaryError(
                    f"Store associated with customer '{user.username}' is inactive."
                )
        return ServiceActor(
            actor_id=actor_id,
            role=ActorRole.CUSTOMER.value,
            customer_id=profile.customer_id.strip(),
            authorized_branch_ids=None,
        )

    if profile.role == UserIdentityProfile.Role.BRANCH_MANAGER:
        if profile.store_id is None or profile.store is None:
            raise AuthenticationBoundaryError(
                f"Branch manager '{user.username}' is not assigned to a store."
            )
        if not profile.store.is_active:
            raise AuthenticationBoundaryError(
                f"Store '{profile.store.code}' for branch manager '{user.username}' is inactive."
            )

        active_branch_ids = frozenset(
            BranchRoleAssignment.objects.filter(
                profile=profile,
                is_active=True,
                branch__is_active=True,
                branch__store_id=profile.store_id,
                branch__store__is_active=True,
            ).values_list('branch_id', flat=True)
        )
        if not active_branch_ids:
            raise AuthenticationBoundaryError(
                f"Branch manager '{user.username}' has no active authorized branch assignments."
            )
        return ServiceActor(
            actor_id=actor_id,
            role=ActorRole.BRANCH_MANAGER.value,
            customer_id=None,
            authorized_branch_ids=active_branch_ids,
        )

    if profile.role == UserIdentityProfile.Role.OWNER:
        if profile.store_id is None or profile.store is None:
            raise AuthenticationBoundaryError(
                f"Store owner '{user.username}' is not assigned to a store."
            )
        if not profile.store.is_active:
            raise AuthenticationBoundaryError(
                f"Store '{profile.store.code}' for store owner '{user.username}' is inactive."
            )

        active_branch_ids = frozenset(
            Branch.objects.filter(
                store_id=profile.store_id,
                store__is_active=True,
                is_active=True,
            ).values_list('id', flat=True)
        )
        if not active_branch_ids:
            raise AuthenticationBoundaryError(
                f"Store '{profile.store.code}' has no active branches for owner '{user.username}'."
            )
        return ServiceActor(
            actor_id=actor_id,
            role=ActorRole.OWNER.value,
            customer_id=None,
            authorized_branch_ids=active_branch_ids,
        )

    raise AuthenticationBoundaryError(f"Unsupported identity profile role: {profile.role}")


def enforce_no_client_identity_spoofing(request, actor: ServiceActor) -> None:
    """
    Validates that a request does not attempt to spoof `role`, `actor_id`, `customer_id`,
    `store_id`, or unauthorized `branch_id` via request body, query params, or headers.
    """
    data = getattr(request, 'data', {})
    if not isinstance(data, dict):
        data = {}
    query_params = getattr(request, 'query_params', {})
    headers = getattr(request, 'headers', {})

    # 1. Check role spoofing
    supplied_role = (
        data.get('role')
        or data.get('actor_role')
        or query_params.get('role')
        or headers.get('X-Actor-Role')
    )
    if supplied_role is not None and str(supplied_role).strip() != actor.role:
        raise AuthenticationBoundaryError(
            "Client-supplied role is not trusted and conflicts with server-resolved identity."
        )

    # 2. Check actor_id spoofing
    supplied_actor_id = (
        data.get('actor_id')
        or query_params.get('actor_id')
        or headers.get('X-Actor-Id')
    )
    if supplied_actor_id is not None and str(supplied_actor_id).strip() != actor.actor_id:
        raise AuthenticationBoundaryError(
            "Client-supplied actor_id is not trusted and conflicts with server-resolved identity."
        )

    # 3. Check customer_id spoofing for CUSTOMER actors
    if actor.role == ActorRole.CUSTOMER.value:
        for raw_cid in (
            data.get('customer_id'),
            query_params.get('customer_id'),
            headers.get('X-Customer-Id'),
        ):
            if raw_cid is not None and str(raw_cid).strip() != (actor.customer_id or ''):
                raise AuthenticationBoundaryError(
                    "Customer actor cannot specify or access a different customer_id."
                )

    # 4. Check store_id spoofing
    supplied_store_id = (
        data.get('store_id')
        or query_params.get('store_id')
        or headers.get('X-Store-Id')
    )
    if supplied_store_id is not None:
        profile = getattr(request.user, 'identity_profile', None)
        try:
            parsed_store_id = int(supplied_store_id)
        except (TypeError, ValueError) as exc:
            raise AuthenticationBoundaryError("Invalid client-supplied store_id.") from exc
        if profile is None or profile.store_id is None or profile.store_id != parsed_store_id:
            raise AuthenticationBoundaryError(
                "Actor is not authorized for the client-supplied store_id."
            )
