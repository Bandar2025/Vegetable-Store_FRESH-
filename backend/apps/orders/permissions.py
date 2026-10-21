"""
Explicit DRF Permission Classes for Customer, Branch Manager, and Store Owner Authorization.
"""

from __future__ import annotations

from rest_framework.exceptions import PermissionDenied
from rest_framework.permissions import BasePermission

from apps.orders.auth import (
    enforce_no_client_identity_spoofing,
    resolve_service_actor_from_user,
)
from apps.orders.models import Order
from apps.orders.services import (
    ActorRole,
    AuthenticationBoundaryError,
    ServiceActor,
)


def get_or_resolve_request_actor(request) -> ServiceActor:
    """
    Resolves and caches the authoritative `ServiceActor` on the DRF `request` object.
    """
    cached = getattr(request, '_resolved_service_actor', None)
    if cached is not None:
        return cached
    actor = resolve_service_actor_from_user(getattr(request, 'user', None))
    enforce_no_client_identity_spoofing(request, actor)
    setattr(request, '_resolved_service_actor', actor)
    return actor


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
