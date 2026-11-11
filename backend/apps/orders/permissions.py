"""
Explicit DRF Permission Classes for Customer, Branch Manager, Store Owner, and Public Guest Catalog Authorization.
"""

from __future__ import annotations

try:
    from rest_framework.exceptions import PermissionDenied
    from rest_framework.permissions import SAFE_METHODS, BasePermission
except ImportError:
    SAFE_METHODS = ('GET', 'HEAD', 'OPTIONS')

    class PermissionDenied(Exception):
        def __init__(self, detail: str = 'You do not have permission to perform this action.'):
            super().__init__(detail)
            self.detail = detail

    class BasePermission:
        def has_permission(self, request, view) -> bool:
            return True

        def has_object_permission(self, request, view, obj) -> bool:
            return True

try:
    from apps.orders.auth import (
        enforce_no_client_identity_spoofing,
        resolve_service_actor_from_user,
    )
    from apps.orders.models import Order
except ImportError:
    Order = object  # type: ignore[assignment,misc]
    enforce_no_client_identity_spoofing = None  # type: ignore[assignment]
    resolve_service_actor_from_user = None  # type: ignore[assignment]

from apps.orders.services import (
    ActorRole,
    AuthenticationBoundaryError,
    ServiceActor,
)

RESTRICTED_GUEST_QUERY_PARAMS = frozenset({
    'branch_id',
    'branch',
    'store_id',
    'role',
    'actor_role',
    'actor_id',
    'customer_id',
})

RESTRICTED_GUEST_HEADERS = frozenset({
    'X-Actor-Role',
    'X-Actor-Id',
    'X-Customer-Id',
    'X-Store-Id',
})


def get_or_resolve_request_actor(request) -> ServiceActor:
    """
    Resolves and caches the authoritative `ServiceActor` on the DRF `request` object.
    """
    cached = getattr(request, '_resolved_service_actor', None)
    if cached is not None:
        return cached
    if resolve_service_actor_from_user is None:
        raise AuthenticationBoundaryError("Authentication runtime is not available.")
    actor = resolve_service_actor_from_user(getattr(request, 'user', None))
    if enforce_no_client_identity_spoofing is not None:
        enforce_no_client_identity_spoofing(request, actor)
    setattr(request, '_resolved_service_actor', actor)
    return actor


def _guest_request_contains_restricted_scope_or_spoofing(request) -> bool:
    """
    Returns True if an unauthenticated guest request attempts to supply branch-scoped
    (`branch_id`, `branch`), store-scoped (`store_id`), or identity-spoofing parameters/headers.
    """
    query_params = getattr(request, 'query_params', None) or getattr(request, 'GET', None) or {}
    for key in RESTRICTED_GUEST_QUERY_PARAMS:
        val = query_params.get(key)
        if val is not None and str(val).strip() != '':
            return True

    headers = getattr(request, 'headers', None) or {}
    for header_name in RESTRICTED_GUEST_HEADERS:
        val = headers.get(header_name)
        if val is not None and str(val).strip() != '':
            return True

    meta = getattr(request, 'META', None) or {}
    for meta_key in ('HTTP_X_ACTOR_ROLE', 'HTTP_X_ACTOR_ID', 'HTTP_X_CUSTOMER_ID', 'HTTP_X_STORE_ID'):
        val = meta.get(meta_key)
        if val is not None and str(val).strip() != '':
            return True

    return False


class IsAuthenticatedActiveProfile(BasePermission):
    """
    Requires an authenticated, active Django User with a valid, active `UserIdentityProfile`
    and active store/branch relationships.
    """

    def has_permission(self, request, view) -> bool:
        user = getattr(request, 'user', None)
        if user is None or not getattr(user, 'is_authenticated', False):
            return False
        try:
            get_or_resolve_request_actor(request)
            return True
        except AuthenticationBoundaryError as exc:
            raise PermissionDenied(detail=str(exc)) from exc


class PublicCatalogReadOrBranchManagerOwnerWritePermission(BasePermission):
    """
    Permission policy for catalog endpoints (`CategoryViewSet`, `ProductViewSet`):
    - Safe read methods (`GET`, `HEAD`, `OPTIONS`):
      - Allowed for unauthenticated guests browsing the public catalog, provided they do NOT
        attempt to query private branch/store overrides (`branch_id`, `branch`, `store_id`)
        or spoof identity headers/params.
      - Allowed for authenticated users with an active `UserIdentityProfile` (validated via
        `get_or_resolve_request_actor`).
    - Unsafe write/management methods (`POST`, `PUT`, `PATCH`, `DELETE`):
      - Unauthenticated requests are rejected (`return False` -> 401 Unauthorized).
      - Authenticated requests without an active `BRANCH_MANAGER` or `OWNER` identity are
        rejected with `PermissionDenied` (403 Forbidden).
    """

    def has_permission(self, request, view) -> bool:
        method = (getattr(request, 'method', '') or '').upper()
        user = getattr(request, 'user', None)
        is_authenticated = bool(user is not None and getattr(user, 'is_authenticated', False))

        if method in SAFE_METHODS:
            if not is_authenticated:
                if _guest_request_contains_restricted_scope_or_spoofing(request):
                    return False
                return True
            try:
                get_or_resolve_request_actor(request)
                return True
            except AuthenticationBoundaryError as exc:
                raise PermissionDenied(detail=str(exc)) from exc

        # Write / management methods (POST, PUT, PATCH, DELETE)
        if not is_authenticated:
            return False

        try:
            actor = get_or_resolve_request_actor(request)
        except AuthenticationBoundaryError as exc:
            raise PermissionDenied(detail=str(exc)) from exc

        if actor.role not in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value):
            raise PermissionDenied(
                detail="Modifying catalog resources requires an active BRANCH_MANAGER or OWNER identity."
            )
        return True


class IsCustomerPermission(IsAuthenticatedActiveProfile):
    """
    Allows access strictly to authenticated users with an active CUSTOMER identity profile.
    """

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        actor = get_or_resolve_request_actor(request)
        if actor.role != ActorRole.CUSTOMER.value:
            raise PermissionDenied(detail="This action requires an active CUSTOMER identity.")
        return True


class IsBranchManagerPermission(IsAuthenticatedActiveProfile):
    """
    Allows access strictly to authenticated users with an active BRANCH_MANAGER identity profile.
    """

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        actor = get_or_resolve_request_actor(request)
        if actor.role != ActorRole.BRANCH_MANAGER.value:
            raise PermissionDenied(detail="This action requires an active BRANCH_MANAGER identity.")
        return True


class IsOwnerPermission(IsAuthenticatedActiveProfile):
    """
    Allows access strictly to authenticated users with an active OWNER identity profile.
    """

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        actor = get_or_resolve_request_actor(request)
        if actor.role != ActorRole.OWNER.value:
            raise PermissionDenied(detail="This action requires an active OWNER identity.")
        return True


class IsBranchManagerOrOwnerPermission(IsAuthenticatedActiveProfile):
    """
    Allows access strictly to authenticated users with an active BRANCH_MANAGER or OWNER identity profile.
    """

    def has_permission(self, request, view) -> bool:
        if not super().has_permission(request, view):
            return False
        actor = get_or_resolve_request_actor(request)
        if actor.role not in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value):
            raise PermissionDenied(
                detail="This action requires an active BRANCH_MANAGER or OWNER identity."
            )
        return True


class OrderObjectPermission(IsAuthenticatedActiveProfile):
    """
    Enforces object-level access control on `Order` instances:
    - CUSTOMER: may only access orders where `order.customer_id == actor.customer_id`.
    - BRANCH_MANAGER: may only access orders where `order.branch_id` is in their active assigned branches.
    - OWNER: may only access orders where `order.branch_id` belongs to an active branch of their active store.
    """

    def has_object_permission(self, request, view, obj: Order) -> bool:
        actor = get_or_resolve_request_actor(request)
        if actor.role == ActorRole.CUSTOMER.value:
            if obj.customer_id != actor.customer_id:
                raise PermissionDenied(
                    detail="Customer is not authorized to access another customer's order."
                )
            return True

        if actor.role in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value):
            if actor.authorized_branch_ids is None or obj.branch_id not in actor.authorized_branch_ids:
                raise PermissionDenied(
                    detail=f"Actor '{actor.actor_id}' is not authorized for branch {obj.branch_id}."
                )
            return True

        raise PermissionDenied(detail="Actor role is not authorized to access orders.")

