from __future__ import annotations

import copy
import re
from typing import Any

try:
    from rest_framework import serializers
except ImportError:
    class _ValidationError(Exception):
        def __init__(self, detail: Any = None):
            self.detail = detail
            super().__init__(str(detail))

    class _Field:
        def __init__(
            self,
            *,
            required: bool = True,
            allow_null: bool = False,
            read_only: bool = False,
            write_only: bool = False,
            default: Any = ...,
        ):
            self.required = required
            self.allow_null = allow_null
            self.read_only = read_only
            self.write_only = write_only
            self.default = default

        def validate_value(self, value: Any) -> Any:
            return value

    class _IntegerField(_Field):
        def __init__(self, *, min_value: int | None = None, max_value: int | None = None, **kwargs):
            super().__init__(**kwargs)
            self.min_value = min_value
            self.max_value = max_value

        def validate_value(self, value: Any) -> int:
            if isinstance(value, bool) or not isinstance(value, int):
                raise _ValidationError("A valid integer is required.")
            if self.min_value is not None and value < self.min_value:
                raise _ValidationError(f"Ensure this value is greater than or equal to {self.min_value}.")
            if self.max_value is not None and value > self.max_value:
                raise _ValidationError(f"Ensure this value is less than or equal to {self.max_value}.")
            return int(value)

    class _CharField(_Field):
        def __init__(
            self,
            *,
            max_length: int | None = None,
            min_length: int | None = None,
            allow_blank: bool = False,
            trim_whitespace: bool = True,
            **kwargs,
        ):
            super().__init__(**kwargs)
            self.max_length = max_length
            self.min_length = min_length
            self.allow_blank = allow_blank
            self.trim_whitespace = trim_whitespace

        def validate_value(self, value: Any) -> str:
            if not isinstance(value, str):
                raise _ValidationError("Not a valid string.")
            cleaned = value.strip() if self.trim_whitespace else value
            if not cleaned and not self.allow_blank:
                raise _ValidationError("This field may not be blank.")
            if self.min_length is not None and len(cleaned) < self.min_length:
                raise _ValidationError(f"Ensure this field has at least {self.min_length} characters.")
            if self.max_length is not None and len(cleaned) > self.max_length:
                raise _ValidationError(f"Ensure this field has no more than {self.max_length} characters.")
            return cleaned

    class _EmailField(_CharField):
        _EMAIL_RE = re.compile(r'^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')

        def validate_value(self, value: Any) -> str:
            cleaned = super().validate_value(value)
            if cleaned and not self._EMAIL_RE.match(cleaned):
                raise _ValidationError("Enter a valid email address.")
            return cleaned

    class _BooleanField(_Field):
        def validate_value(self, value: Any) -> bool:
            if not isinstance(value, bool):
                raise _ValidationError("Must be a valid boolean.")
            return bool(value)

    class _SerializerMeta(type):
        def __new__(mcls, name, bases, attrs):
            declared_fields: dict[str, Any] = {}
            for base in bases:
                if hasattr(base, '_declared_fields'):
                    declared_fields.update(getattr(base, '_declared_fields'))
            for key, val in list(attrs.items()):
                if isinstance(val, _Field):
                    declared_fields[key] = val
            attrs['_declared_fields'] = declared_fields
            return super().__new__(mcls, name, bases, attrs)

    class _BaseSerializer(_Field, metaclass=_SerializerMeta):
        def __init__(
            self,
            instance: Any = None,
            data: Any = ...,
            *,
            many: bool = False,
            allow_empty: bool = True,
            read_only: bool = False,
            required: bool = True,
            context: dict[str, Any] | None = None,
            **kwargs,
        ):
            self.instance = instance
            self.initial_data = data
            self.many = many
            self.allow_empty = allow_empty
            self.read_only = read_only
            self.required = required
            self.context = context or {}
            self._validated_data: Any = None
            self._errors: dict[str, Any] | None = None

        def is_valid(self, *, raise_exception: bool = False) -> bool:
            errors: dict[str, Any] = {}
            validated: dict[str, Any] = {}

            if not isinstance(self.initial_data, dict):
                self._errors = {'non_field_errors': ['Invalid data. Expected a dictionary.']}
                if raise_exception:
                    raise _ValidationError(self._errors)
                return False

            for field_name, field_obj in self._declared_fields.items():
                if getattr(field_obj, 'read_only', False):
                    continue
                has_key = field_name in self.initial_data
                raw_val = self.initial_data.get(field_name)

                if isinstance(field_obj, _BaseSerializer) and field_obj.many:
                    if not has_key or raw_val is None:
                        if field_obj.required:
                            errors[field_name] = ['This field is required.']
                        continue
                    if not isinstance(raw_val, list):
                        errors[field_name] = ['Expected a list of items.']
                        continue
                    if len(raw_val) == 0 and not field_obj.allow_empty:
                        errors[field_name] = ['This list may not be empty.']
                        continue
                    child_cls = field_obj.__class__
                    item_list: list[Any] = []
                    item_errors: list[Any] = []
                    has_item_err = False
                    for raw_elem in raw_val:
                        child = child_cls(data=raw_elem, context=self.context)
                        if child.is_valid(raise_exception=False):
                            item_list.append(child.validated_data)
                            item_errors.append({})
                        else:
                            has_item_err = True
                            item_errors.append(child.errors)
                    if has_item_err:
                        errors[field_name] = item_errors
                    else:
                        validated[field_name] = item_list
                    continue

                if not has_key:
                    if getattr(field_obj, 'default', ...) is not ...:
                        validated[field_name] = copy.deepcopy(field_obj.default)
                    elif field_obj.required:
                        errors[field_name] = ['This field is required.']
                    continue

                if raw_val is None:
                    if getattr(field_obj, 'allow_null', False):
                        validated[field_name] = None
                    else:
                        errors[field_name] = ['This field may not be null.']
                    continue

                try:
                    validated[field_name] = field_obj.validate_value(raw_val)
                except _ValidationError as exc:
                    errors[field_name] = [exc.detail] if isinstance(exc.detail, str) else exc.detail

            if not errors and hasattr(self, 'validate'):
                try:
                    validated = self.validate(validated)
                except _ValidationError as exc:
                    if isinstance(exc.detail, dict):
                        for k, v in exc.detail.items():
                            errors[k] = v if isinstance(v, list) else [v]
                    else:
                        errors['non_field_errors'] = [exc.detail]

            self._errors = errors
            self._validated_data = validated if not errors else {}
            if errors and raise_exception:
                raise _ValidationError(errors)
            return not bool(errors)

        @property
        def errors(self) -> dict[str, Any]:
            if self._errors is None:
                raise RuntimeError("You must call `.is_valid()` before accessing `.errors`.")
            return self._errors

        @property
        def validated_data(self) -> dict[str, Any]:
            if self._validated_data is None:
                raise RuntimeError("You must call `.is_valid()` before accessing `.validated_data`.")
            return self._validated_data

    class _FallbackSerializersModule:
        ValidationError = _ValidationError
        Serializer = _BaseSerializer
        ModelSerializer = _BaseSerializer
        IntegerField = _IntegerField
        CharField = _CharField
        EmailField = _EmailField
        BooleanField = _BooleanField

    serializers = _FallbackSerializersModule()  # type: ignore[assignment]

try:
    from .models import Order, OrderItem, OrderStatusHistory
except ImportError:
    Order = object  # type: ignore[assignment,misc]
    OrderItem = object  # type: ignore[assignment,misc]
    OrderStatusHistory = object  # type: ignore[assignment,misc]

from .services import FORBIDDEN_ITEM_FIELDS, FORBIDDEN_ORDER_CHECKOUT_FIELDS


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
    line_total_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    expected_unit_price_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)

    def to_internal_value(self, data):
        if isinstance(data, dict):
            for bad_field in FORBIDDEN_ITEM_FIELDS:
                if bad_field in data and data.get(bad_field) is not None:
                    raise serializers.ValidationError(
                        {bad_field: f"Client is not permitted to force '{bad_field}'."}
                    )
        return super().to_internal_value(data)

    def validate(self, attrs):
        init_data = getattr(self, 'initial_data', None)
        raw_item = init_data if isinstance(init_data, dict) else {}
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
    warehouse_id = serializers.IntegerField(min_value=1, required=False, allow_null=True)
    idempotency_key = serializers.CharField(max_length=128, required=False, allow_blank=False)
    items = CheckoutItemRequestSerializer(many=True, allow_empty=False)
    # Client-submitted total is ignored in favor of server-side calculation
    total_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    expected_total_halalas = serializers.IntegerField(min_value=0, required=False, allow_null=True)
    reject_client_pricing = serializers.BooleanField(required=False, default=False)

    def to_internal_value(self, data):
        if isinstance(data, dict):
            for bad_field in FORBIDDEN_ORDER_CHECKOUT_FIELDS:
                if bad_field in data and data.get(bad_field) is not None:
                    raise serializers.ValidationError(
                        {bad_field: f"Client is not permitted to force '{bad_field}'."}
                    )
        return super().to_internal_value(data)

    def validate(self, attrs):
        init_data = getattr(self, 'initial_data', None)
        raw_data = init_data if isinstance(init_data, dict) else {}
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
        if resolved_wh_id is None and 'fulfillment_warehouse' in attrs and attrs.get('fulfillment_warehouse') is not None:
            resolved_wh_id = attrs.get('fulfillment_warehouse')
        if resolved_wh_id is None and 'warehouse_id' in attrs and attrs.get('warehouse_id') is not None:
            resolved_wh_id = attrs.get('warehouse_id')
        attrs['fulfillment_warehouse_id'] = int(resolved_wh_id) if resolved_wh_id is not None else None
        attrs['warehouse_id'] = attrs['fulfillment_warehouse_id']

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


