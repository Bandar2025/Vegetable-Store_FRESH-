from rest_framework import serializers
from .models import Order, OrderItem, OrderStatusHistory


class OrderItemSerializer(serializers.ModelSerializer):
    class Meta:
        model = OrderItem
        fields = [
            'id',
            'product',
            'product_name_snapshot',
            'unit_snapshot',
            'quantity_milli',
            'unit_price_halalas',
            'line_total_halalas',
        ]
        # Never trust client-submitted prices, snapshots, or line totals
        read_only_fields = [
            'id',
            'product_name_snapshot',
            'unit_snapshot',
            'unit_price_halalas',
            'line_total_halalas',
        ]


class OrderStatusHistorySerializer(serializers.ModelSerializer):
    class Meta:
        model = OrderStatusHistory
        fields = [
            'id',
            'from_status',
            'to_status',
            'changed_by_actor_id',
            'reason',
            'changed_at',
        ]
        read_only_fields = fields


class OrderSerializer(serializers.ModelSerializer):
    items = OrderItemSerializer(many=True, read_only=True)
    status_history = OrderStatusHistorySerializer(many=True, read_only=True)

    class Meta:
        model = Order
        fields = [
            'id',
            'branch',
            'fulfillment_warehouse',
            'customer_id',
            'items',
            'status_history',
            'total_halalas',
            'timestamp',
            'updated_at',
            'status',
            'idempotency_key',
            'cancellation_restored',
        ]
        # Never trust client-submitted order total_halalas, customer_id, or status
        read_only_fields = [
            'id',
            'customer_id',
            'total_halalas',
            'timestamp',
            'updated_at',
            'status',
            'cancellation_restored',
        ]


class CheckoutItemRequestSerializer(serializers.Serializer):
    product_id = serializers.IntegerField(min_value=1, required=False)
    product = serializers.IntegerField(min_value=1, required=False)
    quantity_milli = serializers.IntegerField(min_value=1)
    # Client-supplied pricing fields are accepted only to be explicitly ignored by server calculation
    unit_price_halalas = serializers.IntegerField(required=False, allow_null=True)
    price_halalas = serializers.IntegerField(required=False, allow_null=True)
    line_total_halalas = serializers.IntegerField(required=False, allow_null=True)

    def validate(self, attrs):
        resolved_product_id = attrs.get('product_id') or attrs.get('product')
        if not resolved_product_id:
            raise serializers.ValidationError({'product_id': 'product_id is required for each checkout item.'})
        attrs['product_id'] = int(resolved_product_id)
        return attrs


class CheckoutRequestSerializer(serializers.Serializer):
    branch_id = serializers.IntegerField(min_value=1, required=False)
    branch = serializers.IntegerField(min_value=1, required=False)
    fulfillment_warehouse_id = serializers.IntegerField(min_value=1, required=False, allow_null=True)
    fulfillment_warehouse = serializers.IntegerField(min_value=1, required=False, allow_null=True)
    idempotency_key = serializers.CharField(max_length=128, required=False, allow_blank=False)
    items = CheckoutItemRequestSerializer(many=True, allow_empty=False)
    # Client-submitted total is ignored in favor of server-side calculation
    total_halalas = serializers.IntegerField(required=False, allow_null=True)

    def validate(self, attrs):
        resolved_branch_id = attrs.get('branch_id') or attrs.get('branch')
        if not resolved_branch_id:
            raise serializers.ValidationError({'branch_id': 'branch_id is required.'})
        attrs['branch_id'] = int(resolved_branch_id)

        resolved_wh_id = attrs.get('fulfillment_warehouse_id')
        if resolved_wh_id is None and 'fulfillment_warehouse' in attrs:
            resolved_wh_id = attrs.get('fulfillment_warehouse')
        attrs['fulfillment_warehouse_id'] = int(resolved_wh_id) if resolved_wh_id is not None else None
        return attrs


class OrderStatusTransitionRequestSerializer(serializers.Serializer):
    to_status = serializers.CharField(max_length=50)
    reason = serializers.CharField(required=False, allow_blank=True, default='')


class CancelOrderRequestSerializer(serializers.Serializer):
    idempotency_key = serializers.CharField(max_length=128, required=False, allow_blank=False)
    reason = serializers.CharField(required=False, allow_blank=True, default='Order cancelled and stock restored')


class AuthLoginRequestSerializer(serializers.Serializer):
    username = serializers.CharField(max_length=150)
    password = serializers.CharField(write_only=True, trim_whitespace=False)


class AuthTokenRefreshRequestSerializer(serializers.Serializer):
    refresh_token = serializers.CharField(write_only=True, required=False, trim_whitespace=True)
    refresh = serializers.CharField(write_only=True, required=False, trim_whitespace=True)

    def validate(self, attrs):
        token = attrs.get('refresh_token') or attrs.get('refresh')
        if not token or not str(token).strip():
            raise serializers.ValidationError({'refresh_token': 'refresh_token is required.'})
        attrs['refresh_token'] = str(token).strip()
        return attrs


class AuthLogoutRequestSerializer(serializers.Serializer):
    all_devices = serializers.BooleanField(required=False, default=False)

