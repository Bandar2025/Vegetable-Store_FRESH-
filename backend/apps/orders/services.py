"""
Atomic Backend Checkout, Order Status Lifecycle, and Single-Execution Order Cancellation Services.

Authentication Boundary Notice:
- The project does not yet have verified production authentication or public RBAC middleware.
- These services MUST NOT be exposed as unauthenticated public HTTP endpoints.
- Callers must supply an explicit `ServiceActor` representing the authenticated/verified actor context.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import sqlite3
from typing import Any, Mapping, Sequence

try:
    from django.core.exceptions import ValidationError as DjangoValidationError
    from django.db import IntegrityError as DjangoIntegrityError, transaction
    from apps.orders.models import (
        IdempotencyKey,
        Order,
        OrderItem,
        OrderStatusHistory,
        calculate_line_total_halalas,
        calculate_order_total_halalas,
    )
    from apps.products.models import (
        MILLI_UNITS_PER_UNIT,
        Branch,
        BranchProductOverride,
        Product,
        StockMovement,
        Warehouse,
        WarehouseStockBalance,
        resolve_effective_price_halalas,
        validate_milli_quantity,
    )

    DJANGO_ORM_AVAILABLE = True
except ImportError:
    DJANGO_ORM_AVAILABLE = False
    MILLI_UNITS_PER_UNIT = 1000

    class DjangoValidationError(Exception):
        pass

    class DjangoIntegrityError(Exception):
        pass

    def resolve_effective_price_halalas(
        base_price_halalas: int,
        override_price_halalas: int | None = None,
    ) -> int:
        if not isinstance(base_price_halalas, int) or isinstance(base_price_halalas, bool):
            raise ValueError("base_price_halalas must be an integer in halalas.")
        if base_price_halalas < 0:
            raise ValueError("base_price_halalas must be non-negative.")
        if override_price_halalas is not None:
            if not isinstance(override_price_halalas, int) or isinstance(override_price_halalas, bool):
                raise ValueError("override_price_halalas must be an integer in halalas when provided.")
            if override_price_halalas < 0:
                raise ValueError("override_price_halalas must be non-negative.")
            return override_price_halalas
        return base_price_halalas

    def validate_milli_quantity(
        quantity_milli: int,
        *,
        is_fractional_allowed: bool = True,
        min_order_quantity_milli: int = 1,
        max_order_quantity_milli: int | None = None,
    ) -> int:
        if not isinstance(quantity_milli, int) or isinstance(quantity_milli, bool):
            raise ValueError("quantity_milli must be an integer representing milli-units.")
        if quantity_milli <= 0:
            raise ValueError("quantity_milli must be strictly positive (> 0).")
        if not is_fractional_allowed and (quantity_milli % MILLI_UNITS_PER_UNIT != 0):
            raise ValueError(
                "Fractional quantities are not permitted for this product; quantity_milli must be a multiple of 1000."
            )
        if min_order_quantity_milli is not None and quantity_milli < min_order_quantity_milli:
            raise ValueError(
                f"quantity_milli ({quantity_milli}) is below minimum order quantity ({min_order_quantity_milli})."
            )
        if max_order_quantity_milli is not None and quantity_milli > max_order_quantity_milli:
            raise ValueError(
                f"quantity_milli ({quantity_milli}) exceeds maximum order quantity ({max_order_quantity_milli})."
            )
        return quantity_milli

    def calculate_line_total_halalas(quantity_milli: int, unit_price_halalas: int) -> int:
        if not isinstance(quantity_milli, int) or isinstance(quantity_milli, bool):
            raise ValueError("quantity_milli must be an integer representing milli-units.")
        if quantity_milli <= 0:
            raise ValueError("quantity_milli must be strictly positive (> 0).")
        if not isinstance(unit_price_halalas, int) or isinstance(unit_price_halalas, bool):
            raise ValueError("unit_price_halalas must be an integer representing halalas.")
        if unit_price_halalas < 0:
            raise ValueError("unit_price_halalas must be non-negative (>= 0).")
        return (quantity_milli * unit_price_halalas + (MILLI_UNITS_PER_UNIT // 2)) // MILLI_UNITS_PER_UNIT

    def calculate_order_total_halalas(line_totals_halalas: list[int] | tuple[int, ...]) -> int:
        total = 0
        for line_total in line_totals_halalas:
            if not isinstance(line_total, int) or isinstance(line_total, bool) or line_total < 0:
                raise ValueError("Each line_total_halalas must be a non-negative integer.")
            total += line_total
        return total


OPERATION_CHECKOUT_ORDER = 'CHECKOUT_ORDER'
OPERATION_CANCEL_ORDER = 'CANCEL_ORDER'

ORDER_STATUS_PENDING = 'PENDING'
ORDER_STATUS_CONFIRMED = 'CONFIRMED'
ORDER_STATUS_PROCESSING = 'PROCESSING'
ORDER_STATUS_COMPLETED = 'COMPLETED'
ORDER_STATUS_CANCELLED = 'CANCELLED'

VALID_ORDER_STATUS_TRANSITIONS: dict[str, frozenset[str]] = {
    ORDER_STATUS_PENDING: frozenset({ORDER_STATUS_CONFIRMED, ORDER_STATUS_CANCELLED}),
    ORDER_STATUS_CONFIRMED: frozenset({ORDER_STATUS_PROCESSING, ORDER_STATUS_CANCELLED}),
    ORDER_STATUS_PROCESSING: frozenset({ORDER_STATUS_COMPLETED, ORDER_STATUS_CANCELLED}),
    ORDER_STATUS_COMPLETED: frozenset(),
    ORDER_STATUS_CANCELLED: frozenset(),
}

MOVEMENT_TYPE_CHECKOUT_DEDUCTION = 'CHECKOUT_DEDUCTION'
MOVEMENT_TYPE_CANCELLATION_RESTORATION = 'CANCELLATION_RESTORATION'


class OrderServiceError(Exception):
    """Base exception for all order service errors."""


class AuthenticationBoundaryError(OrderServiceError):
    """Raised when an internal service call lacks a valid authorized ServiceActor."""


class CheckoutValidationError(OrderServiceError):
    """Raised when checkout input, product availability, or quantity rules fail validation."""


class BranchWarehouseMismatchError(CheckoutValidationError):
    """Raised when the fulfillment warehouse does not belong to the order's branch."""


class MissingStockBalanceError(CheckoutValidationError):
    """Raised when a required WarehouseStockBalance record is missing."""


class InsufficientStockError(CheckoutValidationError):
    """Raised when available warehouse stock is less than the requested quantity_milli."""


class IdempotencyConflictError(OrderServiceError):
    """Raised when an idempotency key is reused with a different request payload."""


class InvalidOrderStatusTransitionError(OrderServiceError):
    """Raised when an order status transition violates the state machine."""


class OrderCancellationError(InvalidOrderStatusTransitionError):
    """Raised when an order cannot be cancelled or has already been cancelled/restored."""


class ImmutableLedgerError(OrderServiceError):
    """Raised when an append-only audit ledger record is modified or deleted."""


class ActorRole(str, Enum):
    CUSTOMER = 'CUSTOMER'
    BRANCH_MANAGER = 'BRANCH_MANAGER'
    OWNER = 'OWNER'
    SYSTEM = 'SYSTEM'


@dataclass(frozen=True)
class ServiceActor:
    """
    Explicit internal actor context required by all order domain service operations.
    Used until production authentication and branch-scoped RBAC tokens are integrated.
    """
    actor_id: str
    role: str = ActorRole.CUSTOMER.value
    customer_id: str | None = None
    authorized_branch_ids: frozenset[int] | None = None


@dataclass(frozen=True)
class CheckoutItemInput:
    """
    Line-item input for atomic checkout.
    Any client-supplied unit_price_halalas or line_total_halalas is ignored in favor of
    server-authoritative catalog/branch-override calculations.
    """
    product_id: int
    quantity_milli: int
    client_unit_price_halalas: int | None = None
    client_line_total_halalas: int | None = None


@dataclass(frozen=True)
class OrderItemSnapshotResult:
    id: int
    product_id: int
    product_name_snapshot: str
    unit_snapshot: str
    quantity_milli: int
    unit_price_halalas: int
    line_total_halalas: int


@dataclass(frozen=True)
class CheckoutResult:
    order_id: int
    branch_id: int
    fulfillment_warehouse_id: int
    customer_id: str
    status: str
    total_halalas: int
    idempotency_key: str
    items: tuple[OrderItemSnapshotResult, ...]
    replayed: bool = False


@dataclass(frozen=True)
class OrderStatusTransitionResult:
    order_id: int
    from_status: str
    to_status: str
    changed_by_actor_id: str
    reason: str


@dataclass(frozen=True)
class CancelOrderResult:
    order_id: int
    branch_id: int
    fulfillment_warehouse_id: int
    from_status: str
    status: str
    total_halalas: int
    cancellation_restored: bool
    idempotency_key: str
    restored_items: tuple[tuple[int, int], ...]
    replayed: bool = False


def install_sqlite_append_only_triggers(conn: sqlite3.Connection) -> None:
    """
    Installs database-level BEFORE UPDATE and BEFORE DELETE triggers on SQLite connections
    for `products_stockmovement`, `orders_orderitem`, and `orders_orderstatushistory`
    so audit ledgers are protected even against raw SQL mutations bypassing ORM save().
    """
    conn.executescript(
        """
        CREATE TRIGGER IF NOT EXISTS trg_stockmovement_no_update
        BEFORE UPDATE ON products_stockmovement
        BEGIN
            SELECT RAISE(ABORT, 'products_stockmovement ledger is append-only; UPDATE is prohibited.');
        END;

        CREATE TRIGGER IF NOT EXISTS trg_stockmovement_no_delete
        BEFORE DELETE ON products_stockmovement
        BEGIN
            SELECT RAISE(ABORT, 'products_stockmovement ledger is append-only; DELETE is prohibited.');
        END;

        CREATE TRIGGER IF NOT EXISTS trg_orderitem_no_update
        BEFORE UPDATE ON orders_orderitem
        BEGIN
            SELECT RAISE(ABORT, 'orders_orderitem snapshots are immutable; UPDATE is prohibited.');
        END;

        CREATE TRIGGER IF NOT EXISTS trg_orderitem_no_delete
        BEFORE DELETE ON orders_orderitem
        BEGIN
            SELECT RAISE(ABORT, 'orders_orderitem snapshots are immutable; DELETE is prohibited.');
        END;

        CREATE TRIGGER IF NOT EXISTS trg_orderstatushistory_no_update
        BEFORE UPDATE ON orders_orderstatushistory
        BEGIN
            SELECT RAISE(ABORT, 'orders_orderstatushistory ledger is append-only; UPDATE is prohibited.');
        END;

        CREATE TRIGGER IF NOT EXISTS trg_orderstatushistory_no_delete
        BEFORE DELETE ON orders_orderstatushistory
        BEGIN
            SELECT RAISE(ABORT, 'orders_orderstatushistory ledger is append-only; DELETE is prohibited.');
        END;
        """
    )


def _validate_actor(actor: ServiceActor | None) -> ServiceActor:
    if actor is None or not isinstance(actor, ServiceActor):
        raise AuthenticationBoundaryError("A valid ServiceActor instance is required.")
    if not isinstance(actor.actor_id, str) or not actor.actor_id.strip():
        raise AuthenticationBoundaryError("actor.actor_id must be a non-empty string.")
    valid_roles = {r.value for r in ActorRole}
    role_val = actor.role.value if isinstance(actor.role, ActorRole) else str(actor.role)
    if role_val not in valid_roles:
        raise AuthenticationBoundaryError(f"Unsupported actor role: {actor.role}")
    return actor


def _validate_idempotency_key(idempotency_key: str) -> str:
    if not isinstance(idempotency_key, str) or not idempotency_key.strip():
        raise CheckoutValidationError("idempotency_key must be a non-empty string.")
    normalized = idempotency_key.strip()
    if len(normalized) > 128:
        raise CheckoutValidationError("idempotency_key cannot exceed 128 characters.")
    return normalized


def _normalize_checkout_items(
    items: Sequence[CheckoutItemInput | Mapping[str, Any]],
) -> list[CheckoutItemInput]:
    if not items or not isinstance(items, (list, tuple)):
        raise CheckoutValidationError("Checkout requires at least one item.")

    normalized: list[CheckoutItemInput] = []
    seen_product_ids: set[int] = set()

    for idx, raw_item in enumerate(items):
        if isinstance(raw_item, CheckoutItemInput):
            product_id = raw_item.product_id
            quantity_milli = raw_item.quantity_milli
            client_unit_price = raw_item.client_unit_price_halalas
            client_line_total = raw_item.client_line_total_halalas
        elif isinstance(raw_item, Mapping):
            product_id = raw_item.get('product_id')
            quantity_milli = raw_item.get('quantity_milli')
            client_unit_price = raw_item.get('unit_price_halalas', raw_item.get('price_halalas'))
            client_line_total = raw_item.get('line_total_halalas')
        else:
            raise CheckoutValidationError(f"Item at index {idx} must be a CheckoutItemInput or mapping.")

        if not isinstance(product_id, int) or isinstance(product_id, bool) or product_id <= 0:
            raise CheckoutValidationError(f"Item at index {idx} has an invalid product_id.")
        if not isinstance(quantity_milli, int) or isinstance(quantity_milli, bool) or quantity_milli <= 0:
            raise CheckoutValidationError(
                f"Item for product_id={product_id} has invalid quantity_milli ({quantity_milli}); must be a positive integer."
            )
        if product_id in seen_product_ids:
            raise CheckoutValidationError(
                f"Duplicate product_id={product_id} in checkout items; combine quantities per product."
            )
        seen_product_ids.add(product_id)
        normalized.append(
            CheckoutItemInput(
                product_id=product_id,
                quantity_milli=quantity_milli,
                client_unit_price_halalas=client_unit_price,
                client_line_total_halalas=client_line_total,
            )
        )

    return normalized


def compute_checkout_request_hash(
    *,
    customer_id: str,
    branch_id: int,
    fulfillment_warehouse_id: int | None,
    items: Sequence[CheckoutItemInput | Mapping[str, Any]],
) -> str:
    """
    Computes a deterministic SHA-256 hash of the canonical checkout request payload.
    Items are canonicalized in deterministic product_id order.
    Client-submitted prices and totals are intentionally excluded from pricing authority.
    """
    normalized_items = _normalize_checkout_items(items)
    canonical_payload = {
        'operation': OPERATION_CHECKOUT_ORDER,
        'customer_id': customer_id.strip(),
        'branch_id': int(branch_id),
        'fulfillment_warehouse_id': int(fulfillment_warehouse_id) if fulfillment_warehouse_id is not None else None,
        'items': [
            {
                'product_id': item.product_id,
                'quantity_milli': item.quantity_milli,
            }
            for item in sorted(normalized_items, key=lambda x: x.product_id)
        ],
    }
    encoded = json.dumps(canonical_payload, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


def compute_cancel_request_hash(*, order_id: int) -> str:
    """
    Computes a deterministic SHA-256 hash of the canonical order cancellation request payload.
    """
    if not isinstance(order_id, int) or isinstance(order_id, bool) or order_id <= 0:
        raise OrderCancellationError("order_id must be a positive integer.")
    canonical_payload = {
        'operation': OPERATION_CANCEL_ORDER,
        'order_id': int(order_id),
    }
    encoded = json.dumps(canonical_payload, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


def _serialize_checkout_result(result: CheckoutResult) -> dict[str, Any]:
    return {
        'order_id': result.order_id,
        'branch_id': result.branch_id,
        'fulfillment_warehouse_id': result.fulfillment_warehouse_id,
        'customer_id': result.customer_id,
        'status': result.status,
        'total_halalas': result.total_halalas,
        'idempotency_key': result.idempotency_key,
        'items': [
            {
                'id': item.id,
                'product_id': item.product_id,
                'product_name_snapshot': item.product_name_snapshot,
                'unit_snapshot': item.unit_snapshot,
                'quantity_milli': item.quantity_milli,
                'unit_price_halalas': item.unit_price_halalas,
                'line_total_halalas': item.line_total_halalas,
            }
            for item in result.items
        ],
    }


def _deserialize_checkout_result(payload: Mapping[str, Any], *, replayed: bool) -> CheckoutResult:
    items = tuple(
        OrderItemSnapshotResult(
            id=int(i['id']),
            product_id=int(i['product_id']),
            product_name_snapshot=str(i['product_name_snapshot']),
            unit_snapshot=str(i['unit_snapshot']),
            quantity_milli=int(i['quantity_milli']),
            unit_price_halalas=int(i['unit_price_halalas']),
            line_total_halalas=int(i['line_total_halalas']),
        )
        for i in payload['items']
    )
    return CheckoutResult(
        order_id=int(payload['order_id']),
        branch_id=int(payload['branch_id']),
        fulfillment_warehouse_id=int(payload['fulfillment_warehouse_id']),
        customer_id=str(payload['customer_id']),
        status=str(payload['status']),
        total_halalas=int(payload['total_halalas']),
        idempotency_key=str(payload['idempotency_key']),
        items=items,
        replayed=replayed,
    )


def _serialize_cancel_result(result: CancelOrderResult) -> dict[str, Any]:
    return {
        'order_id': result.order_id,
        'branch_id': result.branch_id,
        'fulfillment_warehouse_id': result.fulfillment_warehouse_id,
        'from_status': result.from_status,
        'status': result.status,
        'total_halalas': result.total_halalas,
        'cancellation_restored': result.cancellation_restored,
        'idempotency_key': result.idempotency_key,
        'restored_items': [
            {'product_id': prod_id, 'quantity_milli': qty}
            for prod_id, qty in result.restored_items
        ],
    }


def _deserialize_cancel_result(payload: Mapping[str, Any], *, replayed: bool) -> CancelOrderResult:
    restored_items = tuple(
        (int(entry['product_id']), int(entry['quantity_milli']))
        for entry in payload['restored_items']
    )
    return CancelOrderResult(
        order_id=int(payload['order_id']),
        branch_id=int(payload['branch_id']),
        fulfillment_warehouse_id=int(payload['fulfillment_warehouse_id']),
        from_status=str(payload['from_status']),
        status=str(payload['status']),
        total_halalas=int(payload['total_halalas']),
        cancellation_restored=bool(payload['cancellation_restored']),
        idempotency_key=str(payload['idempotency_key']),
        restored_items=restored_items,
        replayed=replayed,
    )


def checkout_order(
    *,
    actor: ServiceActor,
    customer_id: str,
    branch_id: int,
    fulfillment_warehouse_id: int | None = None,
    items: Sequence[CheckoutItemInput | Mapping[str, Any]],
    idempotency_key: str,
    client_total_halalas: int | None = None,
    sqlite_conn: sqlite3.Connection | None = None,
) -> CheckoutResult:
    """
    Executes an atomic, server-authoritative multi-branch order checkout.

    Guarantees:
    - Never trusts client-submitted prices, line totals, or `client_total_halalas`.
    - Recalculates all line totals and order total in integer halalas using half-up rounding:
      `(quantity_milli * unit_price_halalas + 500) // 1000`.
    - Enforces branch & fulfillment warehouse consistency (`warehouse.branch_id == branch_id`).
    - Fails safely if any required `WarehouseStockBalance` row is missing (never creates balances silently).
    - Locks `WarehouseStockBalance` rows in deterministic `(warehouse_id, product_id)` ascending order.
    - Rejects inactive products, unavailable branch overrides, invalid/fractional quantities, and insufficient stock.
    - Enforces request idempotency scoped by `(actor.actor_id, 'CHECKOUT_ORDER', idempotency_key)` and `request_hash`.
    - Records `StockMovement` (`CHECKOUT_DEDUCTION`) and initial `OrderStatusHistory` (`None -> PENDING`).
    """
    validated_actor = _validate_actor(actor)
    if not isinstance(customer_id, str) or not customer_id.strip():
        raise CheckoutValidationError("customer_id must be a non-empty string.")
    normalized_customer_id = customer_id.strip()

    role_val = validated_actor.role.value if isinstance(validated_actor.role, ActorRole) else str(validated_actor.role)
    if role_val == ActorRole.CUSTOMER.value:
        effective_actor_customer = (validated_actor.customer_id or validated_actor.actor_id).strip()
        if effective_actor_customer != normalized_customer_id:
            raise AuthenticationBoundaryError(
                "Customer actor cannot place an order on behalf of a different customer_id."
            )

    if not isinstance(branch_id, int) or isinstance(branch_id, bool) or branch_id <= 0:
        raise CheckoutValidationError("branch_id must be a positive integer.")
    if fulfillment_warehouse_id is not None and (
        not isinstance(fulfillment_warehouse_id, int)
        or isinstance(fulfillment_warehouse_id, bool)
        or fulfillment_warehouse_id <= 0
    ):
        raise CheckoutValidationError("fulfillment_warehouse_id must be a positive integer when provided.")

    if validated_actor.authorized_branch_ids is not None and branch_id not in validated_actor.authorized_branch_ids:
        raise AuthenticationBoundaryError(f"Actor {validated_actor.actor_id} is not authorized for branch {branch_id}.")

    normalized_key = _validate_idempotency_key(idempotency_key)
    normalized_items = _normalize_checkout_items(items)
    # Note: client_total_halalas is intentionally ignored for pricing calculation; server recalculates total_halalas.
    _ = client_total_halalas

    request_hash = compute_checkout_request_hash(
        customer_id=normalized_customer_id,
        branch_id=branch_id,
        fulfillment_warehouse_id=fulfillment_warehouse_id,
        items=normalized_items,
    )

    if sqlite_conn is not None:
        return _checkout_order_sqlite(
            conn=sqlite_conn,
            actor=validated_actor,
            customer_id=normalized_customer_id,
            branch_id=branch_id,
            fulfillment_warehouse_id=fulfillment_warehouse_id,
            items=normalized_items,
            idempotency_key=normalized_key,
            request_hash=request_hash,
        )

    if not DJANGO_ORM_AVAILABLE:
        raise OrderServiceError(
            "Django ORM is not installed in this runtime; pass sqlite_conn for SQLite transactional execution."
        )

    return _checkout_order_django(
        actor=validated_actor,
        customer_id=normalized_customer_id,
        branch_id=branch_id,
        fulfillment_warehouse_id=fulfillment_warehouse_id,
        items=normalized_items,
        idempotency_key=normalized_key,
        request_hash=request_hash,
    )


def _checkout_order_django(
    *,
    actor: ServiceActor,
    customer_id: str,
    branch_id: int,
    fulfillment_warehouse_id: int | None,
    items: list[CheckoutItemInput],
    idempotency_key: str,
    request_hash: str,
) -> CheckoutResult:
    with transaction.atomic():
        existing_idem = (
            IdempotencyKey.objects.select_for_update()
            .filter(
                actor_id=actor.actor_id,
                operation=OPERATION_CHECKOUT_ORDER,
                idempotency_key=idempotency_key,
            )
            .first()
        )
        if existing_idem is not None:
            if existing_idem.request_hash != request_hash:
                raise IdempotencyConflictError(
                    f"Idempotency key '{idempotency_key}' was already used with a different checkout request payload."
                )
            if existing_idem.response_payload:
                return _deserialize_checkout_result(existing_idem.response_payload, replayed=True)

        if Order.objects.select_for_update().filter(idempotency_key=idempotency_key).exists():
            raise IdempotencyConflictError(
                f"Idempotency key '{idempotency_key}' is already claimed by another order."
            )

        branch = (
            Branch.objects.select_related('store')
            .filter(pk=branch_id)
            .first()
        )
        if branch is None:
            raise CheckoutValidationError(f"Branch {branch_id} does not exist.")
        if not branch.is_active or not branch.store.is_active:
            raise CheckoutValidationError(f"Branch {branch.code} or its store is inactive.")

        if fulfillment_warehouse_id is not None:
            warehouse = Warehouse.objects.filter(pk=fulfillment_warehouse_id).first()
            if warehouse is None:
                raise BranchWarehouseMismatchError(f"Warehouse {fulfillment_warehouse_id} does not exist.")
            if warehouse.branch_id != branch.id:
                raise BranchWarehouseMismatchError(
                    f"Warehouse {warehouse.code} (branch_id={warehouse.branch_id}) does not belong to branch {branch.code} (id={branch.id})."
                )
            if not warehouse.is_active:
                raise CheckoutValidationError(f"Warehouse {warehouse.code} is inactive.")
        else:
            warehouse = (
                Warehouse.objects.filter(
                    branch_id=branch.id,
                    is_primary_fulfillment=True,
                    is_active=True,
                )
                .order_by('id')
                .first()
            )
            if warehouse is None:
                raise BranchWarehouseMismatchError(
                    f"No active primary fulfillment warehouse exists for branch {branch.code}."
                )

        items_by_product_id = {item.product_id: item for item in items}
        sorted_product_ids = sorted(items_by_product_id.keys())

        products_by_id = {
            p.id: p
            for p in Product.objects.filter(id__in=sorted_product_ids)
        }
        if len(products_by_id) != len(sorted_product_ids):
            missing_pids = sorted(set(sorted_product_ids) - set(products_by_id.keys()))
            raise CheckoutValidationError(f"Products do not exist: {missing_pids}")

        overrides_by_product_id = {
            ov.product_id: ov
            for ov in BranchProductOverride.objects.filter(
                branch_id=branch.id,
                product_id__in=sorted_product_ids,
            )
        }

        # Lock stock balances in deterministic (warehouse_id, product_id) ascending order
        locked_balances = list(
            WarehouseStockBalance.objects.select_for_update()
            .filter(warehouse_id=warehouse.id, product_id__in=sorted_product_ids)
            .order_by('warehouse_id', 'product_id')
        )
        balances_by_product_id = {b.product_id: b for b in locked_balances}

        prepared_lines: list[dict[str, Any]] = []
        for pid in sorted_product_ids:
            req_item = items_by_product_id[pid]
            product = products_by_id[pid]
            override = overrides_by_product_id.get(pid)

            if not product.is_active:
                raise CheckoutValidationError(f"Product '{product.sku}' is inactive.")
            if override is not None and not override.is_available:
                raise CheckoutValidationError(
                    f"Product '{product.sku}' is marked unavailable in branch '{branch.code}'."
                )

            min_qty = override.min_order_quantity_milli if override is not None else 1
            max_qty = override.max_order_quantity_milli if override is not None else None
            try:
                validate_milli_quantity(
                    req_item.quantity_milli,
                    is_fractional_allowed=product.is_fractional_allowed,
                    min_order_quantity_milli=min_qty,
                    max_order_quantity_milli=max_qty,
                )
            except ValueError as exc:
                raise CheckoutValidationError(str(exc)) from exc

            balance = balances_by_product_id.get(pid)
            if balance is None:
                raise MissingStockBalanceError(
                    f"Missing WarehouseStockBalance for product '{product.sku}' (id={pid}) in warehouse '{warehouse.code}'."
                )

            available_milli = balance.on_hand_milli - balance.reserved_milli
            if available_milli < req_item.quantity_milli:
                raise InsufficientStockError(
                    f"Insufficient available stock for product '{product.sku}': "
                    f"requested={req_item.quantity_milli} milli-units, available={available_milli} milli-units."
                )

            unit_price_halalas = resolve_effective_price_halalas(
                product.price_halalas,
                override.override_price_halalas if override is not None else None,
            )
            line_total_halalas = calculate_line_total_halalas(
                req_item.quantity_milli,
                unit_price_halalas,
            )
            prepared_lines.append(
                {
                    'product': product,
                    'balance': balance,
                    'quantity_milli': req_item.quantity_milli,
                    'unit_price_halalas': unit_price_halalas,
                    'line_total_halalas': line_total_halalas,
                }
            )

        authoritative_total_halalas = calculate_order_total_halalas(
            [line['line_total_halalas'] for line in prepared_lines]
        )

        order = Order.objects.create(
            branch=branch,
            fulfillment_warehouse=warehouse,
            customer_id=customer_id,
            status=Order.OrderStatus.PENDING,
            total_halalas=authoritative_total_halalas,
            idempotency_key=idempotency_key,
            cancellation_restored=False,
        )

        snapshot_results: list[OrderItemSnapshotResult] = []
        for line in prepared_lines:
            product = line['product']
            balance = line['balance']
            qty_milli = line['quantity_milli']
            unit_price = line['unit_price_halalas']
            line_total = line['line_total_halalas']

            order_item = OrderItem.objects.create(
                order=order,
                product=product,
                product_name_snapshot=product.name,
                unit_snapshot=product.unit,
                quantity_milli=qty_milli,
                unit_price_halalas=unit_price,
                line_total_halalas=line_total,
            )

            balance.on_hand_milli -= qty_milli
            balance.version += 1
            balance.full_clean()
            balance.save(update_fields=['on_hand_milli', 'version', 'updated_at'])

            StockMovement.objects.create(
                warehouse=warehouse,
                product=product,
                movement_type=StockMovement.MovementType.CHECKOUT_DEDUCTION,
                quantity_delta_milli=-qty_milli,
                balance_after_milli=balance.on_hand_milli,
                reference_order=order,
                actor_id=actor.actor_id,
                reason=f"Checkout stock deduction for order #{order.id}",
                idempotency_key=f"checkout:order-{order.id}:item-{order_item.id}",
            )

            snapshot_results.append(
                OrderItemSnapshotResult(
                    id=order_item.id,
                    product_id=product.id,
                    product_name_snapshot=order_item.product_name_snapshot,
                    unit_snapshot=order_item.unit_snapshot,
                    quantity_milli=order_item.quantity_milli,
                    unit_price_halalas=order_item.unit_price_halalas,
                    line_total_halalas=order_item.line_total_halalas,
                )
            )

        OrderStatusHistory.objects.create(
            order=order,
            from_status=None,
            to_status=Order.OrderStatus.PENDING,
            changed_by_actor_id=actor.actor_id,
            reason='Order created via atomic checkout',
        )

        result = CheckoutResult(
            order_id=order.id,
            branch_id=branch.id,
            fulfillment_warehouse_id=warehouse.id,
            customer_id=order.customer_id,
            status=order.status,
            total_halalas=order.total_halalas,
            idempotency_key=idempotency_key,
            items=tuple(snapshot_results),
            replayed=False,
        )

        IdempotencyKey.objects.create(
            actor_id=actor.actor_id,
            operation=OPERATION_CHECKOUT_ORDER,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            response_status_code=201,
            response_payload=_serialize_checkout_result(result),
            reference_order=order,
        )

        return result


def _checkout_order_sqlite(
    *,
    conn: sqlite3.Connection,
    actor: ServiceActor,
    customer_id: str,
    branch_id: int,
    fulfillment_warehouse_id: int | None,
    items: list[CheckoutItemInput],
    idempotency_key: str,
    request_hash: str,
) -> CheckoutResult:
    conn.execute('PRAGMA foreign_keys = ON;')
    savepoint_name = 'sp_checkout_order'
    conn.execute(f'SAVEPOINT {savepoint_name};')
    try:
        idem_row = conn.execute(
            """
            SELECT request_hash, response_payload
            FROM orders_idempotencykey
            WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
            """,
            (actor.actor_id, OPERATION_CHECKOUT_ORDER, idempotency_key),
        ).fetchone()

        if idem_row is not None:
            stored_hash, stored_payload_json = idem_row
            if stored_hash != request_hash:
                raise IdempotencyConflictError(
                    f"Idempotency key '{idempotency_key}' was already used with a different checkout request payload."
                )
            payload = json.loads(stored_payload_json)
            conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
            return _deserialize_checkout_result(payload, replayed=True)

        existing_order_with_key = conn.execute(
            "SELECT id FROM orders_order WHERE idempotency_key = ?",
            (idempotency_key,),
        ).fetchone()
        if existing_order_with_key is not None:
            raise IdempotencyConflictError(
                f"Idempotency key '{idempotency_key}' is already claimed by another order."
            )

        branch_row = conn.execute(
            """
            SELECT b.id, b.code, b.is_active, s.is_active
            FROM products_branch b
            JOIN products_store s ON s.id = b.store_id
            WHERE b.id = ?
            """,
            (branch_id,),
        ).fetchone()
        if branch_row is None:
            raise CheckoutValidationError(f"Branch {branch_id} does not exist.")
        b_id, b_code, b_active, s_active = branch_row
        if not bool(b_active) or not bool(s_active):
            raise CheckoutValidationError(f"Branch {b_code} or its store is inactive.")

        if fulfillment_warehouse_id is not None:
            wh_row = conn.execute(
                """
                SELECT id, branch_id, code, is_active
                FROM products_warehouse
                WHERE id = ?
                """,
                (fulfillment_warehouse_id,),
            ).fetchone()
            if wh_row is None:
                raise BranchWarehouseMismatchError(f"Warehouse {fulfillment_warehouse_id} does not exist.")
            wh_id, wh_branch_id, wh_code, wh_active = wh_row
            if wh_branch_id != b_id:
                raise BranchWarehouseMismatchError(
                    f"Warehouse {wh_code} (branch_id={wh_branch_id}) does not belong to branch {b_code} (id={b_id})."
                )
            if not bool(wh_active):
                raise CheckoutValidationError(f"Warehouse {wh_code} is inactive.")
        else:
            wh_row = conn.execute(
                """
                SELECT id, branch_id, code, is_active
                FROM products_warehouse
                WHERE branch_id = ? AND is_primary_fulfillment = 1 AND is_active = 1
                ORDER BY id ASC
                LIMIT 1
                """,
                (b_id,),
            ).fetchone()
            if wh_row is None:
                raise BranchWarehouseMismatchError(
                    f"No active primary fulfillment warehouse exists for branch {b_code}."
                )
            wh_id, wh_branch_id, wh_code, wh_active = wh_row

        items_by_product_id = {item.product_id: item for item in items}
        sorted_product_ids = sorted(items_by_product_id.keys())

        prepared_lines: list[dict[str, Any]] = []
        for pid in sorted_product_ids:
            req_item = items_by_product_id[pid]
            prod_row = conn.execute(
                """
                SELECT id, sku, name, price_halalas, unit, is_fractional_allowed, is_active
                FROM products_product
                WHERE id = ?
                """,
                (pid,),
            ).fetchone()
            if prod_row is None:
                raise CheckoutValidationError(f"Product {pid} does not exist.")
            p_id, p_sku, p_name, p_price_halalas, p_unit, p_frac_allowed, p_active = prod_row
            if not bool(p_active):
                raise CheckoutValidationError(f"Product '{p_sku}' is inactive.")

            ov_row = conn.execute(
                """
                SELECT override_price_halalas, is_available, min_order_quantity_milli, max_order_quantity_milli
                FROM products_branchproductoverride
                WHERE branch_id = ? AND product_id = ?
                """,
                (b_id, p_id),
            ).fetchone()
            override_price = None
            min_qty = 1
            max_qty = None
            if ov_row is not None:
                override_price, ov_available, min_qty, max_qty = ov_row
                if not bool(ov_available):
                    raise CheckoutValidationError(
                        f"Product '{p_sku}' is marked unavailable in branch '{b_code}'."
                    )

            try:
                validate_milli_quantity(
                    req_item.quantity_milli,
                    is_fractional_allowed=bool(p_frac_allowed),
                    min_order_quantity_milli=int(min_qty) if min_qty is not None else 1,
                    max_order_quantity_milli=int(max_qty) if max_qty is not None else None,
                )
            except ValueError as exc:
                raise CheckoutValidationError(str(exc)) from exc

            bal_row = conn.execute(
                """
                SELECT id, on_hand_milli, reserved_milli, version
                FROM products_warehousestockbalance
                WHERE warehouse_id = ? AND product_id = ?
                """,
                (wh_id, p_id),
            ).fetchone()
            if bal_row is None:
                raise MissingStockBalanceError(
                    f"Missing WarehouseStockBalance for product '{p_sku}' (id={p_id}) in warehouse '{wh_code}'."
                )
            bal_id, on_hand_milli, reserved_milli, bal_version = bal_row
            available_milli = int(on_hand_milli) - int(reserved_milli)
            if available_milli < req_item.quantity_milli:
                raise InsufficientStockError(
                    f"Insufficient available stock for product '{p_sku}': "
                    f"requested={req_item.quantity_milli} milli-units, available={available_milli} milli-units."
                )

            unit_price_halalas = resolve_effective_price_halalas(
                int(p_price_halalas),
                int(override_price) if override_price is not None else None,
            )
            line_total_halalas = calculate_line_total_halalas(
                req_item.quantity_milli,
                unit_price_halalas,
            )
            prepared_lines.append(
                {
                    'product_id': int(p_id),
                    'product_name_snapshot': str(p_name),
                    'unit_snapshot': str(p_unit),
                    'quantity_milli': req_item.quantity_milli,
                    'unit_price_halalas': unit_price_halalas,
                    'line_total_halalas': line_total_halalas,
                    'balance_id': int(bal_id),
                    'on_hand_milli': int(on_hand_milli),
                    'version': int(bal_version),
                }
            )

        authoritative_total_halalas = calculate_order_total_halalas(
            [line['line_total_halalas'] for line in prepared_lines]
        )

        cursor = conn.execute(
            """
            INSERT INTO orders_order
            (branch_id, fulfillment_warehouse_id, customer_id, status, total_halalas, idempotency_key, cancellation_restored)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """,
            (
                b_id,
                wh_id,
                customer_id,
                ORDER_STATUS_PENDING,
                authoritative_total_halalas,
                idempotency_key,
            ),
        )
        order_id = int(cursor.lastrowid)

        snapshot_results: list[OrderItemSnapshotResult] = []
        for line in prepared_lines:
            item_cursor = conn.execute(
                """
                INSERT INTO orders_orderitem
                (order_id, product_id, product_name_snapshot, unit_snapshot, quantity_milli, unit_price_halalas, line_total_halalas)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    order_id,
                    line['product_id'],
                    line['product_name_snapshot'],
                    line['unit_snapshot'],
                    line['quantity_milli'],
                    line['unit_price_halalas'],
                    line['line_total_halalas'],
                ),
            )
            order_item_id = int(item_cursor.lastrowid)

            new_on_hand_milli = line['on_hand_milli'] - line['quantity_milli']
            new_version = line['version'] + 1
            conn.execute(
                """
                UPDATE products_warehousestockbalance
                SET on_hand_milli = ?, version = ?
                WHERE id = ?
                """,
                (new_on_hand_milli, new_version, line['balance_id']),
            )

            conn.execute(
                """
                INSERT INTO products_stockmovement
                (warehouse_id, product_id, movement_type, quantity_delta_milli, balance_after_milli, reference_order_id, actor_id, reason, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    wh_id,
                    line['product_id'],
                    MOVEMENT_TYPE_CHECKOUT_DEDUCTION,
                    -line['quantity_milli'],
                    new_on_hand_milli,
                    order_id,
                    actor.actor_id,
                    f"Checkout stock deduction for order #{order_id}",
                    f"checkout:order-{order_id}:item-{order_item_id}",
                ),
            )

            snapshot_results.append(
                OrderItemSnapshotResult(
                    id=order_item_id,
                    product_id=line['product_id'],
                    product_name_snapshot=line['product_name_snapshot'],
                    unit_snapshot=line['unit_snapshot'],
                    quantity_milli=line['quantity_milli'],
                    unit_price_halalas=line['unit_price_halalas'],
                    line_total_halalas=line['line_total_halalas'],
                )
            )

        conn.execute(
            """
            INSERT INTO orders_orderstatushistory
            (order_id, from_status, to_status, changed_by_actor_id, reason)
            VALUES (?, NULL, ?, ?, ?)
            """,
            (order_id, ORDER_STATUS_PENDING, actor.actor_id, 'Order created via atomic checkout'),
        )

        result = CheckoutResult(
            order_id=order_id,
            branch_id=int(b_id),
            fulfillment_warehouse_id=int(wh_id),
            customer_id=customer_id,
            status=ORDER_STATUS_PENDING,
            total_halalas=authoritative_total_halalas,
            idempotency_key=idempotency_key,
            items=tuple(snapshot_results),
            replayed=False,
        )

        conn.execute(
            """
            INSERT INTO orders_idempotencykey
            (actor_id, operation, idempotency_key, request_hash, response_status_code, response_payload, reference_order_id)
            VALUES (?, ?, ?, ?, 201, ?, ?)
            """,
            (
                actor.actor_id,
                OPERATION_CHECKOUT_ORDER,
                idempotency_key,
                request_hash,
                json.dumps(_serialize_checkout_result(result), sort_keys=True),
                order_id,
            ),
        )

        conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
        return result
    except Exception:
        conn.execute(f'ROLLBACK TO SAVEPOINT {savepoint_name};')
        conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
        raise


def transition_order_status(
    *,
    actor: ServiceActor,
    order_id: int,
    to_status: str,
    reason: str = '',
    sqlite_conn: sqlite3.Connection | None = None,
) -> OrderStatusTransitionResult:
    """
    Atomically transitions an order along valid non-cancellation lifecycle states:
    PENDING -> CONFIRMED -> PROCESSING -> COMPLETED.

    Note: Transitioning to CANCELLED via this method is prohibited to ensure stock restoration
    and idempotency are never bypassed; use `cancel_order()` for cancellations.
    """
    validated_actor = _validate_actor(actor)
    role_val = validated_actor.role.value if isinstance(validated_actor.role, ActorRole) else str(validated_actor.role)
    if role_val == ActorRole.CUSTOMER.value:
        raise AuthenticationBoundaryError(
            "Customer actors are not authorized to advance management order fulfillment statuses."
        )
    if not isinstance(order_id, int) or isinstance(order_id, bool) or order_id <= 0:
        raise InvalidOrderStatusTransitionError("order_id must be a positive integer.")
    if not isinstance(to_status, str) or not to_status.strip():
        raise InvalidOrderStatusTransitionError("to_status must be a non-empty status string.")
    normalized_to_status = to_status.strip()

    if normalized_to_status == ORDER_STATUS_CANCELLED:
        raise InvalidOrderStatusTransitionError(
            "Direct transition to CANCELLED is prohibited; call cancel_order() to restore stock atomically."
        )

    effective_reason = reason.strip() if isinstance(reason, str) and reason.strip() else f"Status changed to {normalized_to_status}"

    if sqlite_conn is not None:
        return _transition_order_status_sqlite(
            conn=sqlite_conn,
            actor=validated_actor,
            order_id=order_id,
            to_status=normalized_to_status,
            reason=effective_reason,
        )

    if not DJANGO_ORM_AVAILABLE:
        raise OrderServiceError(
            "Django ORM is not installed in this runtime; pass sqlite_conn for SQLite transactional execution."
        )

    return _transition_order_status_django(
        actor=validated_actor,
        order_id=order_id,
        to_status=normalized_to_status,
        reason=effective_reason,
    )


def _transition_order_status_django(
    *,
    actor: ServiceActor,
    order_id: int,
    to_status: str,
    reason: str,
) -> OrderStatusTransitionResult:
    with transaction.atomic():
        order = Order.objects.select_for_update().filter(pk=order_id).first()
        if order is None:
            raise InvalidOrderStatusTransitionError(f"Order #{order_id} does not exist.")

        if actor.authorized_branch_ids is not None and order.branch_id not in actor.authorized_branch_ids:
            raise AuthenticationBoundaryError(
                f"Actor {actor.actor_id} is not authorized for branch {order.branch_id}."
            )

        from_status = order.status
        if not Order.can_transition(from_status, to_status):
            raise InvalidOrderStatusTransitionError(
                f"Invalid order status transition from {from_status} to {to_status}."
            )

        order.status = to_status
        order.save(update_fields=['status', 'updated_at'])

        OrderStatusHistory.objects.create(
            order=order,
            from_status=from_status,
            to_status=to_status,
            changed_by_actor_id=actor.actor_id,
            reason=reason,
        )

        return OrderStatusTransitionResult(
            order_id=order.id,
            from_status=from_status,
            to_status=to_status,
            changed_by_actor_id=actor.actor_id,
            reason=reason,
        )


def _transition_order_status_sqlite(
    *,
    conn: sqlite3.Connection,
    actor: ServiceActor,
    order_id: int,
    to_status: str,
    reason: str,
) -> OrderStatusTransitionResult:
    conn.execute('PRAGMA foreign_keys = ON;')
    savepoint_name = 'sp_transition_order_status'
    conn.execute(f'SAVEPOINT {savepoint_name};')
    try:
        row = conn.execute(
            "SELECT id, branch_id, status FROM orders_order WHERE id = ?",
            (order_id,),
        ).fetchone()
        if row is None:
            raise InvalidOrderStatusTransitionError(f"Order #{order_id} does not exist.")
        _, o_branch_id, from_status = row

        if actor.authorized_branch_ids is not None and int(o_branch_id) not in actor.authorized_branch_ids:
            raise AuthenticationBoundaryError(
                f"Actor {actor.actor_id} is not authorized for branch {o_branch_id}."
            )

        allowed = VALID_ORDER_STATUS_TRANSITIONS.get(from_status, frozenset())
        if to_status not in allowed:
            raise InvalidOrderStatusTransitionError(
                f"Invalid order status transition from {from_status} to {to_status}."
            )

        conn.execute(
            "UPDATE orders_order SET status = ? WHERE id = ?",
            (to_status, order_id),
        )
        conn.execute(
            """
            INSERT INTO orders_orderstatushistory
            (order_id, from_status, to_status, changed_by_actor_id, reason)
            VALUES (?, ?, ?, ?, ?)
            """,
            (order_id, from_status, to_status, actor.actor_id, reason),
        )

        conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
        return OrderStatusTransitionResult(
            order_id=order_id,
            from_status=from_status,
            to_status=to_status,
            changed_by_actor_id=actor.actor_id,
            reason=reason,
        )
    except Exception:
        conn.execute(f'ROLLBACK TO SAVEPOINT {savepoint_name};')
        conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
        raise


def cancel_order(
    *,
    actor: ServiceActor,
    order_id: int,
    idempotency_key: str,
    reason: str = 'Order cancelled and stock restored',
    sqlite_conn: sqlite3.Connection | None = None,
) -> CancelOrderResult:
    """
    Atomically cancels an order and restores its deducted stock to the fulfillment warehouse
    EXACTLY ONCE.

    Guarantees:
    - Enforces request idempotency scoped by `(actor.actor_id, 'CANCEL_ORDER', idempotency_key)` and `request_hash`.
    - Rejects reuse of an idempotency key with a different `order_id`.
    - Never allows cancellation of a completed/delivered order (`COMPLETED`).
    - Prevents repeated cancellation and double stock restoration across four independent guards:
      1. Lifecycle state machine (`CANCELLED` cannot transition to `CANCELLED`).
      2. `Order.cancellation_restored` boolean flag on the locked `Order` row.
      3. Ledger existence check for `StockMovement(reference_order=order, movement_type='CANCELLATION_RESTORATION')`.
      4. Database `UNIQUE` constraint on `StockMovement.idempotency_key` (`cancel-restore:order-{id}:item-{item_id}`).
    - Locks `WarehouseStockBalance` rows in deterministic `(warehouse_id, product_id)` ascending order.
    - Preserves historical `OrderItem` snapshots and `Order.total_halalas`.
    - Records `StockMovement` (`CANCELLATION_RESTORATION`) and `OrderStatusHistory` (`from_status -> CANCELLED`).
    """
    validated_actor = _validate_actor(actor)
    if not isinstance(order_id, int) or isinstance(order_id, bool) or order_id <= 0:
        raise OrderCancellationError("order_id must be a positive integer.")
    normalized_key = _validate_idempotency_key(idempotency_key)
    effective_reason = (
        reason.strip()
        if isinstance(reason, str) and reason.strip()
        else f"Order #{order_id} cancelled and stock restored"
    )
    request_hash = compute_cancel_request_hash(order_id=order_id)

    if sqlite_conn is not None:
        return _cancel_order_sqlite(
            conn=sqlite_conn,
            actor=validated_actor,
            order_id=order_id,
            idempotency_key=normalized_key,
            request_hash=request_hash,
            reason=effective_reason,
        )

    if not DJANGO_ORM_AVAILABLE:
        raise OrderServiceError(
            "Django ORM is not installed in this runtime; pass sqlite_conn for SQLite transactional execution."
        )

    return _cancel_order_django(
        actor=validated_actor,
        order_id=order_id,
        idempotency_key=normalized_key,
        request_hash=request_hash,
        reason=effective_reason,
    )


def _cancel_order_django(
    *,
    actor: ServiceActor,
    order_id: int,
    idempotency_key: str,
    request_hash: str,
    reason: str,
) -> CancelOrderResult:
    with transaction.atomic():
        existing_idem = (
            IdempotencyKey.objects.select_for_update()
            .filter(
                actor_id=actor.actor_id,
                operation=OPERATION_CANCEL_ORDER,
                idempotency_key=idempotency_key,
            )
            .first()
        )
        if existing_idem is not None:
            if existing_idem.request_hash != request_hash:
                raise IdempotencyConflictError(
                    f"Idempotency key '{idempotency_key}' was already used with a different cancellation payload."
                )
            if existing_idem.response_payload:
                return _deserialize_cancel_result(existing_idem.response_payload, replayed=True)

        order = (
            Order.objects.select_for_update()
            .filter(pk=order_id)
            .first()
        )
        if order is None:
            raise OrderCancellationError(f"Order #{order_id} does not exist.")

        role_val = actor.role.value if isinstance(actor.role, ActorRole) else str(actor.role)
        if role_val == ActorRole.CUSTOMER.value:
            effective_actor_customer = (actor.customer_id or actor.actor_id).strip()
            if effective_actor_customer != order.customer_id:
                raise AuthenticationBoundaryError(
                    "Customer actor is not authorized to cancel another customer's order."
                )
        if actor.authorized_branch_ids is not None and order.branch_id not in actor.authorized_branch_ids:
            raise AuthenticationBoundaryError(
                f"Actor {actor.actor_id} is not authorized for branch {order.branch_id}."
            )

        if order.status in (Order.OrderStatus.COMPLETED, 'DELIVERED'):
            raise InvalidOrderStatusTransitionError(
                f"Order #{order.id} is already completed/delivered ({order.status}) and cannot be cancelled."
            )

        if order.status == Order.OrderStatus.CANCELLED or order.cancellation_restored:
            raise OrderCancellationError(
                f"Order #{order.id} is already cancelled; repeated cancellation and double stock restoration are prohibited."
            )

        if not Order.can_transition(order.status, Order.OrderStatus.CANCELLED):
            raise InvalidOrderStatusTransitionError(
                f"Invalid order status transition from {order.status} to CANCELLED."
            )

        if StockMovement.objects.filter(
            reference_order_id=order.id,
            movement_type=StockMovement.MovementType.CANCELLATION_RESTORATION,
        ).exists():
            raise OrderCancellationError(
                f"Cancellation stock restoration has already been recorded for order #{order.id}."
            )

        if order.fulfillment_warehouse_id is None:
            raise BranchWarehouseMismatchError(
                f"Order #{order.id} does not have an assigned fulfillment warehouse."
            )
        if order.fulfillment_warehouse.branch_id != order.branch_id:
            raise BranchWarehouseMismatchError(
                f"Order #{order.id} fulfillment warehouse does not belong to order branch."
            )

        order_items = list(order.items.order_by('product_id', 'id'))
        if not order_items:
            raise OrderCancellationError(f"Order #{order.id} has no order items to restore.")

        sorted_product_ids = [item.product_id for item in order_items]
        locked_balances = list(
            WarehouseStockBalance.objects.select_for_update()
            .filter(
                warehouse_id=order.fulfillment_warehouse_id,
                product_id__in=sorted_product_ids,
            )
            .order_by('warehouse_id', 'product_id')
        )
        balances_by_product_id = {b.product_id: b for b in locked_balances}

        for item in order_items:
            if item.product_id not in balances_by_product_id:
                raise MissingStockBalanceError(
                    f"Missing WarehouseStockBalance for product_id={item.product_id} in warehouse_id={order.fulfillment_warehouse_id} during cancellation."
                )

        restored_pairs: list[tuple[int, int]] = []
        for item in order_items:
            balance = balances_by_product_id[item.product_id]
            balance.on_hand_milli += item.quantity_milli
            balance.version += 1
            balance.full_clean()
            balance.save(update_fields=['on_hand_milli', 'version', 'updated_at'])

            StockMovement.objects.create(
                warehouse_id=order.fulfillment_warehouse_id,
                product_id=item.product_id,
                movement_type=StockMovement.MovementType.CANCELLATION_RESTORATION,
                quantity_delta_milli=item.quantity_milli,
                balance_after_milli=balance.on_hand_milli,
                reference_order=order,
                actor_id=actor.actor_id,
                reason=reason,
                idempotency_key=f"cancel-restore:order-{order.id}:item-{item.id}",
            )
            restored_pairs.append((item.product_id, item.quantity_milli))

        previous_status = order.status
        order.status = Order.OrderStatus.CANCELLED
        order.cancellation_restored = True
        order.save(update_fields=['status', 'cancellation_restored', 'updated_at'])

        OrderStatusHistory.objects.create(
            order=order,
            from_status=previous_status,
            to_status=Order.OrderStatus.CANCELLED,
            changed_by_actor_id=actor.actor_id,
            reason=reason,
        )

        result = CancelOrderResult(
            order_id=order.id,
            branch_id=order.branch_id,
            fulfillment_warehouse_id=order.fulfillment_warehouse_id,
            from_status=previous_status,
            status=order.status,
            total_halalas=order.total_halalas,
            cancellation_restored=True,
            idempotency_key=idempotency_key,
            restored_items=tuple(restored_pairs),
            replayed=False,
        )

        IdempotencyKey.objects.create(
            actor_id=actor.actor_id,
            operation=OPERATION_CANCEL_ORDER,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            response_status_code=200,
            response_payload=_serialize_cancel_result(result),
            reference_order=order,
        )

        return result


def _cancel_order_sqlite(
    *,
    conn: sqlite3.Connection,
    actor: ServiceActor,
    order_id: int,
    idempotency_key: str,
    request_hash: str,
    reason: str,
) -> CancelOrderResult:
    conn.execute('PRAGMA foreign_keys = ON;')
    savepoint_name = 'sp_cancel_order'
    conn.execute(f'SAVEPOINT {savepoint_name};')
    try:
        idem_row = conn.execute(
            """
            SELECT request_hash, response_payload
            FROM orders_idempotencykey
            WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
            """,
            (actor.actor_id, OPERATION_CANCEL_ORDER, idempotency_key),
        ).fetchone()
        if idem_row is not None:
            stored_hash, stored_payload_json = idem_row
            if stored_hash != request_hash:
                raise IdempotencyConflictError(
                    f"Idempotency key '{idempotency_key}' was already used with a different cancellation payload."
                )
            payload = json.loads(stored_payload_json)
            conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
            return _deserialize_cancel_result(payload, replayed=True)

        order_row = conn.execute(
            """
            SELECT id, branch_id, fulfillment_warehouse_id, customer_id, status, total_halalas, cancellation_restored
            FROM orders_order
            WHERE id = ?
            """,
            (order_id,),
        ).fetchone()
        if order_row is None:
            raise OrderCancellationError(f"Order #{order_id} does not exist.")
        (
            o_id,
            o_branch_id,
            o_wh_id,
            o_customer_id,
            o_status,
            o_total_halalas,
            o_cancel_restored,
        ) = order_row

        role_val = actor.role.value if isinstance(actor.role, ActorRole) else str(actor.role)
        if role_val == ActorRole.CUSTOMER.value:
            effective_actor_customer = (actor.customer_id or actor.actor_id).strip()
            if effective_actor_customer != o_customer_id:
                raise AuthenticationBoundaryError(
                    "Customer actor is not authorized to cancel another customer's order."
                )
        if actor.authorized_branch_ids is not None and int(o_branch_id) not in actor.authorized_branch_ids:
            raise AuthenticationBoundaryError(
                f"Actor {actor.actor_id} is not authorized for branch {o_branch_id}."
            )

        if o_status in (ORDER_STATUS_COMPLETED, 'DELIVERED'):
            raise InvalidOrderStatusTransitionError(
                f"Order #{o_id} is already completed/delivered ({o_status}) and cannot be cancelled."
            )

        if o_status == ORDER_STATUS_CANCELLED or bool(o_cancel_restored):
            raise OrderCancellationError(
                f"Order #{o_id} is already cancelled; repeated cancellation and double stock restoration are prohibited."
            )

        allowed = VALID_ORDER_STATUS_TRANSITIONS.get(o_status, frozenset())
        if ORDER_STATUS_CANCELLED not in allowed:
            raise InvalidOrderStatusTransitionError(
                f"Invalid order status transition from {o_status} to CANCELLED."
            )

        existing_restoration = conn.execute(
            """
            SELECT id
            FROM products_stockmovement
            WHERE reference_order_id = ? AND movement_type = ?
            LIMIT 1
            """,
            (o_id, MOVEMENT_TYPE_CANCELLATION_RESTORATION),
        ).fetchone()
        if existing_restoration is not None:
            raise OrderCancellationError(
                f"Cancellation stock restoration has already been recorded for order #{o_id}."
            )

        if o_wh_id is None:
            raise BranchWarehouseMismatchError(
                f"Order #{o_id} does not have an assigned fulfillment warehouse."
            )
        wh_row = conn.execute(
            "SELECT id, branch_id FROM products_warehouse WHERE id = ?",
            (o_wh_id,),
        ).fetchone()
        if wh_row is None or int(wh_row[1]) != int(o_branch_id):
            raise BranchWarehouseMismatchError(
                f"Order #{o_id} fulfillment warehouse does not belong to order branch."
            )

        item_rows = conn.execute(
            """
            SELECT id, product_id, quantity_milli
            FROM orders_orderitem
            WHERE order_id = ?
            ORDER BY product_id ASC, id ASC
            """,
            (o_id,),
        ).fetchall()
        if not item_rows:
            raise OrderCancellationError(f"Order #{o_id} has no order items to restore.")

        balances_by_product_id: dict[int, tuple[int, int, int]] = {}
        for _, prod_id, _ in item_rows:
            bal_row = conn.execute(
                """
                SELECT id, on_hand_milli, version
                FROM products_warehousestockbalance
                WHERE warehouse_id = ? AND product_id = ?
                """,
                (o_wh_id, prod_id),
            ).fetchone()
            if bal_row is None:
                raise MissingStockBalanceError(
                    f"Missing WarehouseStockBalance for product_id={prod_id} in warehouse_id={o_wh_id} during cancellation."
                )
            balances_by_product_id[int(prod_id)] = (int(bal_row[0]), int(bal_row[1]), int(bal_row[2]))

        restored_pairs: list[tuple[int, int]] = []
        for item_id, prod_id, qty_milli in item_rows:
            bal_id, on_hand_milli, bal_version = balances_by_product_id[int(prod_id)]
            new_on_hand_milli = on_hand_milli + int(qty_milli)
            new_version = bal_version + 1
            conn.execute(
                """
                UPDATE products_warehousestockbalance
                SET on_hand_milli = ?, version = ?
                WHERE id = ?
                """,
                (new_on_hand_milli, new_version, bal_id),
            )
            conn.execute(
                """
                INSERT INTO products_stockmovement
                (warehouse_id, product_id, movement_type, quantity_delta_milli, balance_after_milli, reference_order_id, actor_id, reason, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    o_wh_id,
                    int(prod_id),
                    MOVEMENT_TYPE_CANCELLATION_RESTORATION,
                    int(qty_milli),
                    new_on_hand_milli,
                    o_id,
                    actor.actor_id,
                    reason,
                    f"cancel-restore:order-{o_id}:item-{item_id}",
                ),
            )
            restored_pairs.append((int(prod_id), int(qty_milli)))

        conn.execute(
            """
            UPDATE orders_order
            SET status = ?, cancellation_restored = 1
            WHERE id = ?
            """,
            (ORDER_STATUS_CANCELLED, o_id),
        )
        conn.execute(
            """
            INSERT INTO orders_orderstatushistory
            (order_id, from_status, to_status, changed_by_actor_id, reason)
            VALUES (?, ?, ?, ?, ?)
            """,
            (o_id, o_status, ORDER_STATUS_CANCELLED, actor.actor_id, reason),
        )

        result = CancelOrderResult(
            order_id=int(o_id),
            branch_id=int(o_branch_id),
            fulfillment_warehouse_id=int(o_wh_id),
            from_status=str(o_status),
            status=ORDER_STATUS_CANCELLED,
            total_halalas=int(o_total_halalas),
            cancellation_restored=True,
            idempotency_key=idempotency_key,
            restored_items=tuple(restored_pairs),
            replayed=False,
        )

        conn.execute(
            """
            INSERT INTO orders_idempotencykey
            (actor_id, operation, idempotency_key, request_hash, response_status_code, response_payload, reference_order_id)
            VALUES (?, ?, ?, ?, 200, ?, ?)
            """,
            (
                actor.actor_id,
                OPERATION_CANCEL_ORDER,
                idempotency_key,
                request_hash,
                json.dumps(_serialize_cancel_result(result), sort_keys=True),
                o_id,
            ),
        )

        conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
        return result
    except Exception:
        conn.execute(f'ROLLBACK TO SAVEPOINT {savepoint_name};')
        conn.execute(f'RELEASE SAVEPOINT {savepoint_name};')
        raise
