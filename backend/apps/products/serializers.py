from rest_framework import serializers
from .models import (
    Branch,
    BranchProductOverride,
    Category,
    Product,
    StockMovement,
    Store,
    Warehouse,
    WarehouseStockBalance,
)


class CategorySerializer(serializers.ModelSerializer):
    class Meta:
        model = Category
        fields = ['id', 'name', 'display_order', 'is_active']


class ProductSerializer(serializers.ModelSerializer):
    category = CategorySerializer(read_only=True)
    effective_price_halalas = serializers.SerializerMethodField()
    is_available_in_branch = serializers.SerializerMethodField()

    class Meta:
        model = Product
        fields = [
            'id',
            'sku',
            'barcode',
            'name',
            'category',
            'price_halalas',
            'effective_price_halalas',
            'is_available_in_branch',
            'description',
            'image_url',
            'unit',
            'is_fractional_allowed',
            'is_active',
        ]

    def get_effective_price_halalas(self, obj: Product) -> int:
        branch = self.context.get('branch')
        return obj.get_effective_price_halalas(branch=branch)

    def get_is_available_in_branch(self, obj: Product) -> bool:
        branch = self.context.get('branch')
        return obj.is_available_in_branch(branch=branch)
