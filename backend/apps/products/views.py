from __future__ import annotations

from typing import Any, Callable

from apps.orders.permissions import (
    PermissionDenied,
    PublicCatalogReadOrBranchManagerOwnerWritePermission,
    get_or_resolve_request_actor,
)
from apps.orders.services import ActorRole, AuthenticationBoundaryError, ServiceActor

try:
    from rest_framework import viewsets
    from .models import Branch, Category, Product
    from .serializers import CategorySerializer, ProductSerializer

    DRF_VIEWSETS_AVAILABLE = True
except ImportError:
    DRF_VIEWSETS_AVAILABLE = False

    class _FallbackReadOnlyModelViewSet:
        queryset = None
        serializer_class = None
        permission_classes: list[Any] = []
        request: Any = None

        def get_serializer_context(self) -> dict[str, Any]:
            return {'request': getattr(self, 'request', None)}

    class _FallbackViewsetsNamespace:
        ReadOnlyModelViewSet = _FallbackReadOnlyModelViewSet

    viewsets = _FallbackViewsetsNamespace()  # type: ignore[assignment]
    Branch = None  # type: ignore[assignment,misc]
    Category = None  # type: ignore[assignment,misc]
    Product = None  # type: ignore[assignment,misc]
    CategorySerializer = None  # type: ignore[assignment,misc]
    ProductSerializer = None  # type: ignore[assignment,misc]


def resolve_catalog_branch_context(
    request: Any,
    *,
    actor_resolver: Callable[[Any], ServiceActor] = get_or_resolve_request_actor,
    branch_lookup: Callable[[int], Any] | None = None,
) -> Any:
    """
    Resolves and validates an optional `branch_id` / `branch` query parameter for `ProductViewSet`.
    - Public unauthenticated guests are never allowed to resolve branch-specific overrides.
    - `BRANCH_MANAGER` and `OWNER` actors may only query branches within `actor.authorized_branch_ids`.
    - Inactive branches or branches belonging to inactive stores are rejected.
    """
    query_params = getattr(request, 'query_params', None) or getattr(request, 'GET', None) or {}
    branch_param = query_params.get('branch_id') or query_params.get('branch')
    if branch_param is None or str(branch_param).strip() == '':
        return None

    user = getattr(request, 'user', None)
    if user is None or not getattr(user, 'is_authenticated', False):
        raise PermissionDenied(
            detail="Authentication is required to access branch-specific catalog data."
        )

    try:
        actor = actor_resolver(request)
    except AuthenticationBoundaryError as exc:
        raise PermissionDenied(detail=str(exc)) from exc

    try:
        branch_id = int(str(branch_param).strip())
    except (TypeError, ValueError) as exc:
        raise PermissionDenied(detail="Invalid branch_id query parameter.") from exc

    if branch_id <= 0:
        raise PermissionDenied(detail="Invalid branch_id query parameter.")

    if actor.role in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value):
        if actor.authorized_branch_ids is None or branch_id not in actor.authorized_branch_ids:
            raise PermissionDenied(
                detail=f"Actor '{actor.actor_id}' is not authorized for branch {branch_id}."
            )

    if branch_lookup is not None:
        branch = branch_lookup(branch_id)
    elif Branch is not None:
        branch = (
            Branch.objects.select_related('store')
            .filter(pk=branch_id, is_active=True, store__is_active=True)
            .first()
        )
    else:
        branch = None

    if branch is None:
        raise PermissionDenied(detail=f"Branch {branch_id} is not active or does not exist.")

    return branch


class CategoryViewSet(viewsets.ReadOnlyModelViewSet):
    queryset = (
        Category.objects.filter(is_active=True).order_by('display_order', 'name')
        if Category is not None
        else None
    )
    serializer_class = CategorySerializer
    permission_classes = [PublicCatalogReadOrBranchManagerOwnerWritePermission]


class ProductViewSet(viewsets.ReadOnlyModelViewSet):
    queryset = (
        Product.objects.filter(is_active=True, category__is_active=True)
        .select_related('category')
        .prefetch_related('branch_overrides')
        if Product is not None
        else None
    )
    serializer_class = ProductSerializer
    permission_classes = [PublicCatalogReadOrBranchManagerOwnerWritePermission]

    def get_serializer_context(self):
        context = super().get_serializer_context()
        branch = resolve_catalog_branch_context(self.request)
        if branch is not None:
            context['branch'] = branch
        return context

