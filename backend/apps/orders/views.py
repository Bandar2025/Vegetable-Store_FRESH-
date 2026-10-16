from rest_framework import viewsets, status
from rest_framework.response import Response
from .models import Order
from .serializers import OrderSerializer
from ..products.models import Product

class OrderViewSet(viewsets.ModelViewSet):
    queryset = Order.objects.all()
    serializer_class = OrderSerializer

    def create(self, request, *args, **kwargs):
        # Server-side validation logic would go here
        return super().create(request, *args, **kwargs)
