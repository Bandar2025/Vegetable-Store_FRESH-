from rest_framework import serializers
from .models import Order, OrderItem, OrderStatusHistory
from .services import FORBIDDEN_ITEM_FIELDS

FORBIDDEN_ORDER_CHECKOUT_FIELDS = frozenset({
    'subtotal_halalas',
    'subtotal',
    'total',
    'discount_halalas',
    'delivery_fee_halalas',
    'effective_price_halalas',
    'override_price_halalas',
    'stock_quantity',
    'on_hand_milli',
    'reserved_milli',
    'available_milli',
    'stock_milli',
    'status',
    'cancellation_restored',
    'replayed',
})


class OrderItemSerializer(serializers.ModelSerializer):
    product_id = serializers.IntegerField(read_only=True)

    class Meta:
        model = OrderItem
        fields = [
            'id',
            'product',
            'product_id',
            'product_name_snapshot',
            'unit_snapshot',
            'quantity_milli',
            'unit_price_halalas',
            'line_total_halalas',
        ]
        # Never trust client-submitted prices, snapshots, or line totals
        read_only_fields = [
            'id',
            'product_id',
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
    branch_id = serializers.IntegerField(read_only=True)
    fulfillment_warehouse_id = serializers.IntegerField(read_only=True, allow_null=True)
    items = OrderItemSerializer(many=True, read_only=True)
    status_history = OrderStatusHistorySerializer(many=True, read_only=True)

    class Meta:
        model = Order
        fields = [
            'id',
            'branch',
            'branch_id',
            'fulfillment_warehouse',
            'fulfillment_warehouse_id',
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
            'branch_id',
            'fulfillment_warehouse_id',
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
    unit_price_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    price_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    line_total_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    expected_unit_price_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)

    def validate(self, attrs):
        raw_item = self.initial_data if isinstance(self.initial_data, dict) else {}
        for bad_field in FORBIDDEN_ITEM_FIELDS:
            if bad_field in raw_item and raw_item.get(bad_field) is not None:
                raise serializers.ValidationError(
                    {bad_field: f"Client is not permitted to force '{bad_field}'."}
                )
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
    total_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    expected_total_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)

    def validate(self, attrs):
        raw_data = self.initial_data if isinstance(self.initial_data, dict) else {}
        for bad_field in FORBIDDEN_ORDER_CHECKOUT_FIELDS:
            if bad_field in raw_data and raw_data.get(bad_field) is not None:
                raise serializers.ValidationError(
                    {bad_field: f"Client is not permitted to force '{bad_field}'."}
                )

        resolved_branch_id = attrs.get('branch_id') or attrs.get('branch')
        if not resolved_branch_id:
            raise serializers.ValidationError({'branch_id': 'branch_id is required.'})
        attrs['branch_id'] = int(resolved_branch_id)

        resolved_wh_id = attrs.get('fulfillment_warehouse_id')
        if resolved_wh_id is None and 'fulfillment_warehouse' in attrs:
            resolved_wh_id = attrs.get('fulfillment_warehouse')
        attrs['fulfillment_warehouse_id'] = int(resolved_wh_id) if resolved_wh_id is not None else None

        seen_pids: set[int] = set()
        for item in attrs.get('items', []):
            pid = int(item['product_id'])
            if pid in seen_pids:
                raise serializers.ValidationError(
                    {'items': f"Duplicate product_id={pid} in checkout items; combine quantities per product."}
                )
            seen_pids.add(pid)
        return attrs


class OrderStatusTransitionRequestSerializer(serializers.Serializer):
    to_status = serializers.CharField(max_length=50)
    reason = serializers.CharField(required=False, allow_blank=True, default='')


class CancelOrderRequestSerializer(serializers.Serializer):
    idempotency_key = serializers.CharField(max_length=128, required=False, allow_blank=False)
    reason = serializers.CharField(required=False, allow_blank=True, default='Order cancelled and stock restored')


class AuthLoginRequestSerializer(serializers.Serializer):
    username = serializers.CharField(max_length=254, required=False, allow_blank=True)
    email = serializers.CharField(max_length=254, required=False, allow_blank=True)
    password = serializers.CharField(write_only=True, trim_whitespace=False)

    def validate(self, attrs):
        identifier = (attrs.get('username') or attrs.get('email') or '').strip()
        if not identifier:
            raise serializers.ValidationError({'username': 'username or email is required.'})
        attrs['username'] = identifier
        return attrs


class AuthRegisterRequestSerializer(serializers.Serializer):
    username = serializers.CharField(max_length=150, required=False, allow_blank=True)
    email = serializers.EmailField(max_length=254, required=True)
    full_name = serializers.CharField(max_length=150, required=False, allow_blank=True)
    name = serializers.CharField(max_length=150, required=False, allow_blank=True)
    phone_number = serializers.CharField(max_length=32, required=False, allow_blank=True)
    phone = serializers.CharField(max_length=32, required=False, allow_blank=True)
    password = serializers.CharField(write_only=True, min_length=8, trim_whitespace=False)
    confirm_password = serializers.CharField(
        write_only=True,
        required=False,
        allow_blank=True,
        trim_whitespace=False,
    )

    def validate(self, attrs):
        from .auth import (
            CustomerRegistrationValidationError,
            validate_and_normalize_customer_registration_payload,
        )

        raw_payload = self.initial_data if isinstance(self.initial_data, dict) else dict(attrs)
        request = self.context.get('request') if isinstance(self.context, dict) else None
        try:
            normalized = validate_and_normalize_customer_registration_payload(
                raw_payload,
                request=request,
            )
        except CustomerRegistrationValidationError as exc:
            raise serializers.ValidationError(exc.errors) from exc
        attrs.update(normalized)
        return attrs


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


