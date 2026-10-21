from rest_framework import viewsets
from rest_framework.exceptions import PermissionDenied
from rest_framework.permissions import IsAuthenticated

from apps.orders.permissions import (
    IsAuthenticatedActiveProfile,
    get_or_resolve_request_actor,
)
from apps.orders.services import ActorRole
from .models import Branch, Category, Product
from .serializers import CategorySerializer, ProductSerializer


class CategoryViewSet(viewsets.ReadOnlyModelViewSet):
    queryset = Category.objects.filter(is_active=True).order_by('display_order', 'name')
    serializer_class = CategorySerializer
    permission_classes = [IsAuthenticated, IsAuthenticatedActiveProfile]


class ProductViewSet(viewsets.ReadOnlyModelViewSet):
    queryset = Product.objects.filter(is_active=True).select_related('category').prefetch_related('branch_overrides')
    serializer_class = ProductSerializer
    permission_classes = [IsAuthenticated, IsAuthenticatedActiveProfile]

    def get_serializer_context(self):
        context = super().get_serializer_context()
        branch_param = self.request.query_params.get('branch_id') or self.request.query_params.get('branch')
        if branch_param is not None:
            actor = get_or_resolve_request_actor(self.request)
            try:
                branch_id = int(branch_param)
            except (TypeError, ValueError) as exc:
                raise PermissionDenied(detail="Invalid branch_id query parameter.") from exc
            if (
                actor.role in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value)
                and actor.authorized_branch_ids is not None
                and branch_id not in actor.authorized_branch_ids
            ):
                raise PermissionDenied(
                    detail=f"Actor '{actor.actor_id}' is not authorized for branch {branch_id}."
                )
            branch = Branch.objects.select_related('store').filter(pk=branch_id).first()
            context['branch'] = branch
        return context
