import ast
from pathlib import Path
import sqlite3
import unittest

from apps.orders.services import (
    MOVEMENT_TYPE_CANCELLATION_RESTORATION,
    MOVEMENT_TYPE_CHECKOUT_DEDUCTION,
    OPERATION_CANCEL_ORDER,
    OPERATION_CHECKOUT_ORDER,
    ORDER_STATUS_CANCELLED,
    ORDER_STATUS_COMPLETED,
    ORDER_STATUS_CONFIRMED,
    ORDER_STATUS_PENDING,
    ORDER_STATUS_PROCESSING,
    ActorRole,
    AuthenticationBoundaryError,
    BranchWarehouseMismatchError,
    CheckoutItemInput,
    CheckoutValidationError,
    IdempotencyConflictError,
    InsufficientStockError,
    InvalidOrderStatusTransitionError,
    MissingStockBalanceError,
    OrderCancellationError,
    ServiceActor,
     calculate_line_total_halalas,
    calculate_order_total_halalas,
    cancel_order,
    checkout_order,
    install_sqlite_append_only_triggers,
    transition_order_status,
)

try:
    from django.core.exceptions import ValidationError
    from django.db import IntegrityError, transaction
    from django.db.models import ProtectedError
    from django.test import TestCase as DjangoTestCase

    from apps.orders.models import (
        IdempotencyKey,
        Order,
        OrderItem,
        OrderStatusHistory,
    )
    from apps.products.models import (
        Branch,
        BranchProductOverride,
        Category,
        Product,
        StockMovement,
        Store,
        Warehouse,
        WarehouseStockBalance,
    )

    DJANGO_AVAILABLE = True
except ImportError:
    DJANGO_AVAILABLE = False
    DjangoTestCase = unittest.TestCase


BACKEND_ROOT = Path(__file__).resolve().parent.parent.parent
ORDERS_MODELS_PATH = BACKEND_ROOT / 'apps' / 'orders' / 'models.py'
ORDERS_MIGRATION_0001_PATH = BACKEND_ROOT / 'apps' / 'orders' / 'migrations' / '0001_initial.py'
ORDERS_MIGRATION_0002_PATH = BACKEND_ROOT / 'apps' / 'orders' / 'migrations' / '0002_audit_ledger_append_only_triggers.py'
ORDERS_SERIALIZERS_PATH = BACKEND_ROOT / 'apps' / 'orders' / 'serializers.py'
ORDERS_SERVICES_PATH = BACKEND_ROOT / 'apps' / 'orders' / 'services.py'
ORDERS_VIEWS_PATH = BACKEND_ROOT / 'apps' / 'orders' / 'views.py'


def create_full_sqlite_schema(conn: sqlite3.Connection) -> None:
    """
    Creates the complete multi-branch relational schema across apps.products and apps.orders
    in SQLite3 with PRAGMA foreign_keys = ON and database-level BEFORE UPDATE/DELETE append-only
    triggers on `products_stockmovement`, `orders_orderitem`, and `orders_orderstatushistory`.
    """
    conn.execute('PRAGMA foreign_keys = ON;')
    conn.executescript(
        """
        CREATE TABLE products_store (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code VARCHAR(50) NOT NULL UNIQUE CHECK (code <> ''),
            name VARCHAR(200) NOT NULL CHECK (name <> ''),
            is_active BOOLEAN NOT NULL DEFAULT 1
        );

        CREATE TABLE products_branch (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            store_id INTEGER NOT NULL REFERENCES products_store(id) ON DELETE RESTRICT,
            code VARCHAR(50) NOT NULL CHECK (code <> ''),
            name VARCHAR(200) NOT NULL CHECK (name <> ''),
            is_active BOOLEAN NOT NULL DEFAULT 1,
            CONSTRAINT uniq_branch_code_per_store UNIQUE (store_id, code)
        );

        CREATE TABLE products_warehouse (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            code VARCHAR(50) NOT NULL CHECK (code <> ''),
            name VARCHAR(200) NOT NULL CHECK (name <> ''),
            is_primary_fulfillment BOOLEAN NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            CONSTRAINT uniq_warehouse_code_per_branch UNIQUE (branch_id, code)
        );

        CREATE TABLE products_category (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name VARCHAR(100) NOT NULL UNIQUE CHECK (name <> ''),
            display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
            is_active BOOLEAN NOT NULL DEFAULT 1
        );

        CREATE TABLE products_product (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sku VARCHAR(100) NOT NULL UNIQUE CHECK (sku <> ''),
            barcode VARCHAR(100) NULL UNIQUE,
            name VARCHAR(200) NOT NULL CHECK (name <> ''),
            category_id INTEGER NOT NULL REFERENCES products_category(id) ON DELETE RESTRICT,
            price_halalas INTEGER NOT NULL CHECK (price_halalas >= 0),
            unit VARCHAR(50) NOT NULL CHECK (unit <> ''),
            is_fractional_allowed BOOLEAN NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1
        );

        CREATE TABLE products_branchproductoverride (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            override_price_halalas INTEGER NULL,
            is_available BOOLEAN NOT NULL DEFAULT 1,
            min_order_quantity_milli INTEGER NOT NULL DEFAULT 1000,
            max_order_quantity_milli INTEGER NULL,
            CONSTRAINT uniq_branch_product_override UNIQUE (branch_id, product_id),
            CONSTRAINT branch_override_price_halalas_gte_0 CHECK (
                override_price_halalas IS NULL OR override_price_halalas >= 0
            ),
            CONSTRAINT branch_override_min_qty_milli_gt_0 CHECK (min_order_quantity_milli > 0),
            CONSTRAINT branch_override_max_gte_min_qty_milli CHECK (
                max_order_quantity_milli IS NULL OR max_order_quantity_milli >= min_order_quantity_milli
            )
        );

        CREATE TABLE products_warehousestockbalance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            warehouse_id INTEGER NOT NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            on_hand_milli INTEGER NOT NULL DEFAULT 0,
            reserved_milli INTEGER NOT NULL DEFAULT 0,
            version INTEGER NOT NULL DEFAULT 1,
            CONSTRAINT uniq_warehouse_product_stock_balance UNIQUE (warehouse_id, product_id),
            CONSTRAINT stock_balance_on_hand_milli_gte_0 CHECK (on_hand_milli >= 0),
            CONSTRAINT stock_balance_reserved_milli_gte_0 CHECK (reserved_milli >= 0),
            CONSTRAINT stock_balance_reserved_lte_on_hand CHECK (reserved_milli <= on_hand_milli),
            CONSTRAINT stock_balance_version_gte_1 CHECK (version >= 1)
        );

        CREATE TABLE orders_order (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            fulfillment_warehouse_id INTEGER NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            customer_id VARCHAR(100) NOT NULL,
            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
            total_halalas INTEGER NOT NULL DEFAULT 0,
            idempotency_key VARCHAR(128) NULL UNIQUE,
            cancellation_restored BOOLEAN NOT NULL DEFAULT 0,
            timestamp TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT order_total_halalas_gte_0 CHECK (total_halalas >= 0),
            CONSTRAINT order_customer_id_non_empty CHECK (customer_id <> ''),
            CONSTRAINT order_status_valid_choice CHECK (
                status IN ('PENDING', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED')
            )
        );

        CREATE TABLE orders_orderitem (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            order_id INTEGER NOT NULL REFERENCES orders_order(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            product_name_snapshot VARCHAR(200) NOT NULL,
            unit_snapshot VARCHAR(50) NOT NULL,
            quantity_milli INTEGER NOT NULL,
            unit_price_halalas INTEGER NOT NULL,
            line_total_halalas INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT order_item_quantity_milli_gt_0 CHECK (quantity_milli > 0),
            CONSTRAINT order_item_unit_price_halalas_gte_0 CHECK (unit_price_halalas >= 0),
            CONSTRAINT order_item_line_total_halalas_gte_0 CHECK (line_total_halalas >= 0),
            CONSTRAINT order_item_prod_name_snap_non_empty CHECK (product_name_snapshot <> ''),
            CONSTRAINT order_item_unit_snap_non_empty CHECK (unit_snapshot <> '')
        );

        CREATE TABLE orders_orderstatushistory (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            order_id INTEGER NOT NULL REFERENCES orders_order(id) ON DELETE RESTRICT,
            from_status VARCHAR(50) NULL,
            to_status VARCHAR(50) NOT NULL,
            changed_by_actor_id VARCHAR(100) NOT NULL DEFAULT '',
            reason TEXT NOT NULL DEFAULT '',
            changed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT order_status_hist_to_valid CHECK (
                to_status IN ('PENDING', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED')
            ),
            CONSTRAINT order_status_hist_changed CHECK (
                from_status IS NULL OR from_status <> to_status
            )
        );

        CREATE TABLE orders_idempotencykey (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            actor_id VARCHAR(100) NOT NULL,
            operation VARCHAR(64) NOT NULL,
            idempotency_key VARCHAR(128) NOT NULL,
            request_hash VARCHAR(64) NOT NULL,
            response_status_code INTEGER NULL,
            response_payload TEXT NULL,
            reference_order_id INTEGER NULL REFERENCES orders_order(id) ON DELETE SET NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            expires_at TEXT NULL,
            CONSTRAINT uniq_actor_operation_idempotency_key UNIQUE (actor_id, operation, idempotency_key),
            CONSTRAINT idempotency_actor_id_non_empty CHECK (actor_id <> ''),
            CONSTRAINT idempotency_operation_non_empty CHECK (operation <> ''),
            CONSTRAINT idempotency_key_non_empty CHECK (idempotency_key <> ''),
            CONSTRAINT idempotency_req_hash_non_empty CHECK (request_hash <> '')
        );

        CREATE TABLE products_stockmovement (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            warehouse_id INTEGER NOT NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            movement_type VARCHAR(40) NOT NULL,
            quantity_delta_milli INTEGER NOT NULL,
            balance_after_milli INTEGER NOT NULL,
            reference_order_id INTEGER NULL REFERENCES orders_order(id) ON DELETE RESTRICT,
            actor_id VARCHAR(100) NOT NULL DEFAULT '',
            reason TEXT NOT NULL,
            idempotency_key VARCHAR(128) NULL UNIQUE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT stock_movement_delta_milli_non_zero CHECK (quantity_delta_milli <> 0),
            CONSTRAINT stock_movement_balance_after_milli_gte_0 CHECK (balance_after_milli >= 0),
            CONSTRAINT stock_movement_reason_non_empty CHECK (reason <> '')
        );
        """
    )
    install_sqlite_append_only_triggers(conn)


class OrdersModelStructureAndMigrationTests(unittest.TestCase):
    """
    Verifies AST model definitions, field types, serializer read-only guards,
    services locking/transaction patterns, authentication boundary in views, and migrations.
    """

    def setUp(self):
        self.models_source = ORDERS_MODELS_PATH.read_text(encoding='utf-8')
        self.models_tree = ast.parse(self.models_source)
        self.mig_0001_source = ORDERS_MIGRATION_0001_PATH.read_text(encoding='utf-8')
        self.mig_0002_source = ORDERS_MIGRATION_0002_PATH.read_text(encoding='utf-8')
        self.serializers_source = ORDERS_SERIALIZERS_PATH.read_text(encoding='utf-8')
        self.services_source = ORDERS_SERVICES_PATH.read_text(encoding='utf-8')
        self.views_source = ORDERS_VIEWS_PATH.read_text(encoding='utf-8')

    def test_all_four_order_and_idempotency_models_defined(self):
        class_names = {
            node.name for node in self.models_tree.body if isinstance(node, ast.ClassDef)
        }
        expected_models = {
            'Order',
            'OrderItem',
            'OrderStatusHistory',
            'IdempotencyKey',
        }
        self.assertTrue(expected_models.issubset(class_names))
        self.assertIn('objects = AppendOnlyManager()', self.models_source)

    def test_order_item_snapshot_fields_and_integer_halalas_milli_units(self):
        self.assertNotIn('FloatField', self.models_source)
        self.assertNotIn('DecimalField', self.models_source)
        self.assertIn('product_name_snapshot = models.CharField(max_length=200)', self.models_source)
        self.assertIn('unit_snapshot = models.CharField(max_length=50)', self.models_source)
        self.assertIn('quantity_milli = models.BigIntegerField()', self.models_source)
        self.assertIn('unit_price_halalas = models.BigIntegerField()', self.models_source)
        self.assertIn('line_total_halalas = models.BigIntegerField()', self.models_source)
        self.assertIn('total_halalas = models.BigIntegerField(default=0)', self.models_source)

    def test_serializers_and_views_enforce_server_calculation_and_auth_boundary(self):
        self.assertIn("'unit_price_halalas'", self.serializers_source)
        self.assertIn("'line_total_halalas'", self.serializers_source)
        self.assertIn("'total_halalas'", self.serializers_source)
        self.assertIn("read_only_fields", self.serializers_source)
        self.assertIn("ReadOnlyModelViewSet", self.views_source)
        self.assertIn("IsAuthenticated", self.views_source)
        self.assertIn("HTTP_403_FORBIDDEN", self.views_source)

    def test_services_use_transaction_atomic_and_deterministic_select_for_update(self):
        self.assertIn("with transaction.atomic():", self.services_source)
        self.assertIn("select_for_update()", self.services_source)
        self.assertIn(".order_by('warehouse_id', 'product_id')", self.services_source)

    def test_orders_migration_dependencies_and_models(self):
        self.assertIn("('products', '0001_initial')", self.mig_0001_source)
        for model_name in ('Order', 'OrderItem', 'OrderStatusHistory', 'IdempotencyKey'):
            self.assertIn(f"name='{model_name}'", self.mig_0001_source)
        self.assertIn("('orders', '0001_initial')", self.mig_0002_source)
        self.assertIn("trg_orderitem_no_update", self.mig_0002_source)
        self.assertIn("trg_orderstatushistory_no_update", self.mig_0002_source)


class OrdersSnapshotsConstraintsAndCurrencyTests(unittest.TestCase):
    """
    Tests exact halala/milli-unit arithmetic, historical order snapshot immutability,
    status history constraints, idempotency key uniqueness, FK protection, and
    database-level audit ledger immutability triggers in SQLite3.
    """

    def setUp(self):
        self.conn = sqlite3.connect(':memory:')
        create_full_sqlite_schema(self.conn)
        self.conn.execute("INSERT INTO products_store (id, code, name) VALUES (1, 'FRESH-KSA', 'فريش السعودية')")
        self.conn.execute("INSERT INTO products_branch (id, store_id, code, name) VALUES (1, 1, 'RUH-01', 'فرع الرياض')")
        self.conn.execute(
            "INSERT INTO products_warehouse (id, branch_id, code, name, is_primary_fulfillment) VALUES (1, 1, 'WH-01', 'مستودع الرياض', 1)"
        )
        self.conn.execute("INSERT INTO products_category (id, name, display_order) VALUES (1, 'خضروات', 1)")
        self.conn.execute(
            """
            INSERT INTO products_product (id, sku, name, category_id, price_halalas, unit, is_fractional_allowed)
            VALUES (1, 'SKU-CUCUMBER', 'خيار بلدي', 1, 650, 'كجم', 1)
            """
        )

    def tearDown(self):
        self.conn.close()

    def test_fractional_milli_units_and_exact_halala_line_totals(self):
        self.assertEqual(calculate_line_total_halalas(1500, 650), 975)
        self.assertEqual(calculate_line_total_halalas(250, 795), 199)
        self.assertEqual(calculate_line_total_halalas(333, 1000), 333)
        self.assertEqual(calculate_order_total_halalas([975, 199, 333]), 1507)

        with self.assertRaises(ValueError):
            calculate_line_total_halalas(0, 650)
        with self.assertRaises(ValueError):
            calculate_line_total_halalas(-500, 650)
        with self.assertRaises(ValueError):
            calculate_line_total_halalas(1000, -1)
        with self.assertRaises(ValueError):
            calculate_line_total_halalas(1.5, 650)  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            calculate_line_total_halalas(1000, 6.50)  # type: ignore[arg-type]

    def test_historical_order_item_snapshot_isolated_from_later_catalog_changes(self):
        line_total = calculate_line_total_halalas(1500, 650)
        self.conn.execute(
            """
            INSERT INTO orders_order (id, branch_id, fulfillment_warehouse_id, customer_id, status, total_halalas, idempotency_key)
            VALUES (100, 1, 1, 'cust-1', 'PENDING', ?, 'idem-order-100')
            """,
            (line_total,),
        )
        self.conn.execute(
            """
            INSERT INTO orders_orderitem
            (id, order_id, product_id, product_name_snapshot, unit_snapshot, quantity_milli, unit_price_halalas, line_total_halalas)
            VALUES (1, 100, 1, 'خيار بلدي', 'كجم', 1500, 650, ?)
            """,
            (line_total,),
        )

        self.conn.execute(
            """
            UPDATE products_product
            SET name = 'خيار محميات فاخر', unit = 'صندوق', price_halalas = 2400
            WHERE id = 1
            """
        )

        row = self.conn.execute(
            """
            SELECT oi.product_name_snapshot, oi.unit_snapshot, oi.quantity_milli,
                   oi.unit_price_halalas, oi.line_total_halalas, o.total_halalas
            FROM orders_orderitem oi
            JOIN orders_order o ON o.id = oi.order_id
            WHERE oi.id = 1
            """
        ).fetchone()
        self.assertEqual(row, ('خيار بلدي', 'كجم', 1500, 650, 975, 975))

        # Database triggers block raw SQL UPDATE and DELETE on orders_orderitem
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("UPDATE orders_orderitem SET unit_price_halalas = 2400 WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM orders_orderitem WHERE id = 1")

    def test_order_and_order_item_check_constraints_and_status_history(self):
        self.conn.execute(
            """
            INSERT INTO orders_order (id, branch_id, fulfillment_warehouse_id, customer_id, status, total_halalas)
            VALUES (1, 1, 1, 'cust-1', 'PENDING', 650)
            """
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO orders_order (branch_id, customer_id, status, total_halalas)
                VALUES (1, 'cust-1', 'INVALID_STATE', 650)
                """
            )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO orders_order (branch_id, customer_id, status, total_halalas)
                VALUES (1, 'cust-1', 'PENDING', -10)
                """
            )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO orders_orderitem
                (order_id, product_id, product_name_snapshot, unit_snapshot, quantity_milli, unit_price_halalas, line_total_halalas)
                VALUES (1, 1, 'خيار بلدي', 'كجم', 0, 650, 0)
                """
            )
        self.conn.execute(
            """
            INSERT INTO orders_orderstatushistory (id, order_id, from_status, to_status, changed_by_actor_id, reason)
            VALUES (1, 1, NULL, 'PENDING', 'cust-1', 'إنشاء الطلب')
            """
        )
        self.conn.execute(
            """
            INSERT INTO orders_orderstatushistory (id, order_id, from_status, to_status, changed_by_actor_id, reason)
            VALUES (2, 1, 'PENDING', 'CONFIRMED', 'owner-1', 'تأكيد الطلب')
            """
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO orders_orderstatushistory (order_id, from_status, to_status, changed_by_actor_id)
                VALUES (1, 'CONFIRMED', 'CONFIRMED', 'owner-1')
                """
            )
        # Database triggers block raw SQL UPDATE and DELETE on orders_orderstatushistory
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("UPDATE orders_orderstatushistory SET to_status = 'CANCELLED' WHERE id = 2")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM orders_orderstatushistory WHERE id = 2")

    def test_idempotency_key_uniqueness_and_foreign_key_deletion_policies(self):
        self.conn.execute(
            """
            INSERT INTO orders_order (id, branch_id, fulfillment_warehouse_id, customer_id, status, total_halalas, idempotency_key)
            VALUES (1, 1, 1, 'cust-1', 'PENDING', 650, 'idem-checkout-1')
            """
        )
        self.conn.execute(
            """
            INSERT INTO orders_idempotencykey
            (actor_id, operation, idempotency_key, request_hash, response_status_code, reference_order_id)
            VALUES ('cust-1', 'CHECKOUT_ORDER', 'idem-checkout-1', 'sha256-abc', 201, 1)
            """
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO orders_idempotencykey
                (actor_id, operation, idempotency_key, request_hash, response_status_code, reference_order_id)
                VALUES ('cust-1', 'CHECKOUT_ORDER', 'idem-checkout-1', 'sha256-xyz', 201, 1)
                """
            )
        self.conn.execute(
            """
            INSERT INTO orders_idempotencykey
            (actor_id, operation, idempotency_key, request_hash, response_status_code, reference_order_id)
            VALUES ('owner-1', 'CANCEL_ORDER', 'idem-checkout-1', 'sha256-cancel', 200, 1)
            """
        )

        self.conn.execute(
            """
            INSERT INTO orders_orderitem
            (order_id, product_id, product_name_snapshot, unit_snapshot, quantity_milli, unit_price_halalas, line_total_halalas)
            VALUES (1, 1, 'خيار بلدي', 'كجم', 1000, 650, 650)
            """
        )
        self.conn.execute(
            """
            INSERT INTO products_stockmovement
            (warehouse_id, product_id, movement_type, quantity_delta_milli, balance_after_milli, reference_order_id, reason)
            VALUES (1, 1, 'CHECKOUT_DEDUCTION', -1000, 4000, 1, 'خصم للطلب رقم #1')
            """
        )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_product WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM orders_order WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_branch WHERE id = 1")


class AtomicBackendCheckoutAndCancellationServiceTests(unittest.TestCase):
    """
    Comprehensive domain service tests for Micro Task 7B-2:
    1. Successful checkout with multiple items, including fractional milli-unit quantities.
    2. Branch price override applied during checkout.
    3. Client-submitted price/total ignored in favor of server-side halala calculation.
    4. Checkout rejection for insufficient stock with full atomic rollback.
    5. Checkout rejection for warehouse/branch mismatch or missing stock balance.
    6. Idempotent checkout replay returning the same order without duplicate stock deduction.
    7. Idempotency key conflict when reused with a different payload.
    8. Valid status progression and invalid status transition rejection.
    9. Cancellation restoring stock once, recording stock movement and status history.
    10. Repeated cancellation / double stock restoration prevention.
    11. Cancellation rejection for delivered/completed order.
    """

    def setUp(self):
        self.conn = sqlite3.connect(':memory:')
        create_full_sqlite_schema(self.conn)

        # Seed store, 2 branches, and 2 warehouses
        self.conn.execute("INSERT INTO products_store (id, code, name, is_active) VALUES (1, 'FRESH-KSA', 'فريش السعودية', 1)")
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (1, 1, 'RUH-01', 'فرع الرياض العليا', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (2, 1, 'JED-01', 'فرع جدة التحلية', 1)"
        )
        self.conn.execute(
            """
            INSERT INTO products_warehouse (id, branch_id, code, name, is_primary_fulfillment, is_active)
            VALUES (1, 1, 'WH-RUH-MAIN', 'مستودع الرياض الرئيسي', 1, 1)
            """
        )
        self.conn.execute(
            """
            INSERT INTO products_warehouse (id, branch_id, code, name, is_primary_fulfillment, is_active)
            VALUES (2, 2, 'WH-JED-MAIN', 'مستودع جدة الرئيسي', 1, 1)
            """
        )
        self.conn.execute("INSERT INTO products_category (id, name, display_order) VALUES (1, 'خضروات وفواكه', 1)")

        # Product 1: Fractional cucumber (650 halalas / kg)
        self.conn.execute(
            """
            INSERT INTO products_product (id, sku, name, category_id, price_halalas, unit, is_fractional_allowed, is_active)
            VALUES (1, 'SKU-CUCUMBER-KG', 'خيار بلدي', 1, 650, 'كجم', 1, 1)
            """
        )
        # Product 2: Non-fractional dates box (2500 halalas / box)
        self.conn.execute(
            """
            INSERT INTO products_product (id, sku, name, category_id, price_halalas, unit, is_fractional_allowed, is_active)
            VALUES (2, 'SKU-DATES-BOX', 'تمر سكري فاخر', 1, 2500, 'علبة', 0, 1)
            """
        )
        # Product 3: Fractional tomato (795 halalas / kg)
        self.conn.execute(
            """
            INSERT INTO products_product (id, sku, name, category_id, price_halalas, unit, is_fractional_allowed, is_active)
            VALUES (3, 'SKU-TOMATO-KG', 'طماطم محميات', 1, 795, 'كجم', 1, 1)
            """
        )
        # Product 4: Product without a WarehouseStockBalance row in WH-RUH-MAIN
        self.conn.execute(
            """
            INSERT INTO products_product (id, sku, name, category_id, price_halalas, unit, is_fractional_allowed, is_active)
            VALUES (4, 'SKU-NO-BALANCE', 'منتج بدون رصيد مستودع', 1, 1200, 'حبة', 0, 1)
            """
        )

        # Seed stock balances in Warehouse 1 (Branch 1)
        # Product 1: 10,000 milli-units (10.000 kg) on hand, 0 reserved
        self.conn.execute(
            """
            INSERT INTO products_warehousestockbalance (id, warehouse_id, product_id, on_hand_milli, reserved_milli, version)
            VALUES (1, 1, 1, 10000, 0, 1)
            """
        )
        # Product 2: 5,000 milli-units (5 boxes) on hand, 1,000 reserved -> 4,000 available
        self.conn.execute(
            """
            INSERT INTO products_warehousestockbalance (id, warehouse_id, product_id, on_hand_milli, reserved_milli, version)
            VALUES (2, 1, 2, 5000, 1000, 1)
            """
        )
        # Product 3: 8,000 milli-units (8.000 kg) on hand, 0 reserved
        self.conn.execute(
            """
            INSERT INTO products_warehousestockbalance (id, warehouse_id, product_id, on_hand_milli, reserved_milli, version)
            VALUES (3, 1, 3, 8000, 0, 1)
            """
        )

        self.customer_actor = ServiceActor(
            actor_id='cust-101',
            role=ActorRole.CUSTOMER.value,
            customer_id='cust-101',
        )
        self.owner_actor = ServiceActor(
            actor_id='owner-1',
            role=ActorRole.OWNER.value,
            authorized_branch_ids=frozenset({1, 2}),
        )

    def tearDown(self):
        self.conn.close()

    def test_01_successful_checkout_with_multiple_items_including_fractional_quantity(self):
        # Order items submitted in reverse product_id order (3, 2, 1) to verify deterministic lock/processing order
        result = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[
                # Product 3: 0.333 kg * 795 halalas/kg = (333 * 795 + 500) // 1000 = 265 halalas
                CheckoutItemInput(product_id=3, quantity_milli=333),
                # Product 2: 2.000 boxes * 2500 halalas/box = 5000 halalas
                CheckoutItemInput(product_id=2, quantity_milli=2000),
                # Product 1: 1.500 kg * 650 halalas/kg = 975 halalas
                CheckoutItemInput(product_id=1, quantity_milli=1500),
            ],
            idempotency_key='idem-checkout-multi-01',
            sqlite_conn=self.conn,
        )

        self.assertFalse(result.replayed)
        self.assertEqual(result.status, ORDER_STATUS_PENDING)
        # 975 + 5000 + 265 = 6240 halalas
        self.assertEqual(result.total_halalas, 6240)
        self.assertEqual(len(result.items), 3)
        self.assertEqual([item.product_id for item in result.items], [1, 2, 3])
        self.assertEqual([item.line_total_halalas for item in result.items], [975, 5000, 265])

        # Verify warehouse stock balances deducted and versions incremented
        balances = self.conn.execute(
            """
            SELECT product_id, on_hand_milli, reserved_milli, version
            FROM products_warehousestockbalance
            WHERE warehouse_id = 1
            ORDER BY product_id ASC
            """
        ).fetchall()
        self.assertEqual(
            balances,
            [
                (1, 8500, 0, 2),
                (2, 3000, 1000, 2),
                (3, 7667, 0, 2),
            ],
        )

        # Verify 3 CHECKOUT_DEDUCTION stock movements recorded
        movements = self.conn.execute(
            """
            SELECT product_id, movement_type, quantity_delta_milli, balance_after_milli, reference_order_id
            FROM products_stockmovement
            WHERE reference_order_id = ?
            ORDER BY product_id ASC
            """,
            (result.order_id,),
        ).fetchall()
        self.assertEqual(
            movements,
            [
                (1, MOVEMENT_TYPE_CHECKOUT_DEDUCTION, -1500, 8500, result.order_id),
                (2, MOVEMENT_TYPE_CHECKOUT_DEDUCTION, -2000, 3000, result.order_id),
                (3, MOVEMENT_TYPE_CHECKOUT_DEDUCTION, -333, 7667, result.order_id),
            ],
        )

        # Verify initial OrderStatusHistory record (NULL -> PENDING)
        hist = self.conn.execute(
            """
            SELECT from_status, to_status, changed_by_actor_id
            FROM orders_orderstatushistory
            WHERE order_id = ?
            """,
            (result.order_id,),
        ).fetchall()
        self.assertEqual(hist, [(None, ORDER_STATUS_PENDING, 'cust-101')])

    def test_02_branch_price_override_and_availability_applied_during_checkout(self):
        # Set branch override for Product 1 in Branch 1: override_price_halalas = 500 (instead of base 650)
        self.conn.execute(
            """
            INSERT INTO products_branchproductoverride
            (branch_id, product_id, override_price_halalas, is_available, min_order_quantity_milli, max_order_quantity_milli)
            VALUES (1, 1, 500, 1, 500, 5000)
            """
        )

        result = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[CheckoutItemInput(product_id=1, quantity_milli=1500)],
            idempotency_key='idem-branch-override-01',
            sqlite_conn=self.conn,
        )
        # 1.500 kg * 500 halalas/kg = 750 halalas (using branch override price, not 650 base price)
        self.assertEqual(result.items[0].unit_price_halalas, 500)
        self.assertEqual(result.items[0].line_total_halalas, 750)
        self.assertEqual(result.total_halalas, 750)

        # Quantity below branch min_order_quantity_milli (500) is rejected
        with self.assertRaises(CheckoutValidationError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[CheckoutItemInput(product_id=1, quantity_milli=250)],
                idempotency_key='idem-branch-override-min-fail',
                sqlite_conn=self.conn,
            )

        # Mark product unavailable in Branch 1 and verify checkout rejection
        self.conn.execute(
            "UPDATE products_branchproductoverride SET is_available = 0 WHERE branch_id = 1 AND product_id = 1"
        )
        with self.assertRaises(CheckoutValidationError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[CheckoutItemInput(product_id=1, quantity_milli=1000)],
                idempotency_key='idem-branch-override-unavail',
                sqlite_conn=self.conn,
            )

    def test_03_client_submitted_prices_and_totals_ignored_in_favor_of_server_calculation(self):
        # Client attempts to spoof unit_price_halalas=1, line_total_halalas=1, client_total_halalas=1
        result = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[
                {
                    'product_id': 1,
                    'quantity_milli': 2000,
                    'unit_price_halalas': 1,
                    'line_total_halalas': 1,
                },
                CheckoutItemInput(
                    product_id=2,
                    quantity_milli=1000,
                    client_unit_price_halalas=5,
                    client_line_total_halalas=5,
                ),
            ],
            idempotency_key='idem-spoofed-price-01',
            client_total_halalas=6,
            sqlite_conn=self.conn,
        )

        # Authoritative calculation:
        # Product 1: 2.000 kg * 650 = 1300 halalas
        # Product 2: 1.000 box * 2500 = 2500 halalas
        # Total = 3800 halalas
        self.assertEqual(result.items[0].unit_price_halalas, 650)
        self.assertEqual(result.items[0].line_total_halalas, 1300)
        self.assertEqual(result.items[1].unit_price_halalas, 2500)
        self.assertEqual(result.items[1].line_total_halalas, 2500)
        self.assertEqual(result.total_halalas, 3800)

    def test_04_checkout_rejection_for_insufficient_stock_rolls_back_completely(self):
        # Fractional quantity (1500) for non-fractional Product 2 is rejected
        with self.assertRaises(CheckoutValidationError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[CheckoutItemInput(product_id=2, quantity_milli=1500)],
                idempotency_key='idem-fractional-not-allowed',
                sqlite_conn=self.conn,
            )

        # Product 1 has 10,000 available (sufficient for 2,000)
        # Product 2 has 5,000 on_hand - 1,000 reserved = 4,000 available (insufficient for 5,000!)
        with self.assertRaises(InsufficientStockError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[
                    CheckoutItemInput(product_id=1, quantity_milli=2000),
                    CheckoutItemInput(product_id=2, quantity_milli=5000),
                ],
                idempotency_key='idem-insufficient-stock-01',
                sqlite_conn=self.conn,
            )

        # Verify zero orders, zero order items, zero stock movements, zero idempotency records persisted
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM orders_order").fetchone()[0], 0)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM orders_orderitem").fetchone()[0], 0)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM products_stockmovement").fetchone()[0], 0)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM orders_orderstatushistory").fetchone()[0], 0)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM orders_idempotencykey").fetchone()[0], 0)

        # Verify Product 1 and Product 2 stock balances are completely untouched
        balances = self.conn.execute(
            "SELECT product_id, on_hand_milli, reserved_milli, version FROM products_warehousestockbalance WHERE warehouse_id = 1 ORDER BY product_id"
        ).fetchall()
        self.assertEqual(
            balances,
            [
                (1, 10000, 0, 1),
                (2, 5000, 1000, 1),
                (3, 8000, 0, 1),
            ],
        )

    def test_05_checkout_rejection_for_warehouse_branch_mismatch_or_missing_stock_balance(self):
        # Warehouse 2 belongs to Branch 2, not Branch 1
        with self.assertRaises(BranchWarehouseMismatchError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=2,
                items=[CheckoutItemInput(product_id=1, quantity_milli=1000)],
                idempotency_key='idem-mismatch-wh-branch',
                sqlite_conn=self.conn,
            )

        # Product 4 has no WarehouseStockBalance row in Warehouse 1; must fail safely without creating a balance row
        with self.assertRaises(MissingStockBalanceError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[
                    CheckoutItemInput(product_id=1, quantity_milli=1000),
                    CheckoutItemInput(product_id=4, quantity_milli=1000),
                ],
                idempotency_key='idem-missing-balance',
                sqlite_conn=self.conn,
            )

        # Verify no stock balance row was silently created for Product 4 and Product 1 was not deducted
        self.assertIsNone(
            self.conn.execute(
                "SELECT id FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 4"
            ).fetchone()
        )
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            10000,
        )

    def test_06_idempotent_checkout_replay_returns_same_order_without_duplicate_deduction(self):
        items_payload = [
            CheckoutItemInput(product_id=1, quantity_milli=1250),
            CheckoutItemInput(product_id=2, quantity_milli=1000),
        ]
        first_result = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=items_payload,
            idempotency_key='idem-replay-key-01',
            sqlite_conn=self.conn,
        )
        self.assertFalse(first_result.replayed)

        # Replay identical checkout request
        second_result = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=list(reversed(items_payload)),
            idempotency_key='idem-replay-key-01',
            sqlite_conn=self.conn,
        )
        self.assertTrue(second_result.replayed)
        self.assertEqual(second_result.order_id, first_result.order_id)
        self.assertEqual(second_result.total_halalas, first_result.total_halalas)
        self.assertEqual(second_result.items, first_result.items)

        # Only 1 Order and 1 set of stock deductions exist
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM orders_order").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM products_stockmovement").fetchone()[0], 2)
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            8750,
        )

    def test_07_idempotency_key_conflict_when_reused_with_different_payload(self):
        checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[CheckoutItemInput(product_id=1, quantity_milli=1000)],
            idempotency_key='idem-conflict-key-01',
            sqlite_conn=self.conn,
        )

        # Reusing the same idempotency_key with a different quantity_milli must raise IdempotencyConflictError
        with self.assertRaises(IdempotencyConflictError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[CheckoutItemInput(product_id=1, quantity_milli=2000)],
                idempotency_key='idem-conflict-key-01',
                sqlite_conn=self.conn,
            )

        # Reusing the same idempotency_key with a different product_id must raise IdempotencyConflictError
        with self.assertRaises(IdempotencyConflictError):
            checkout_order(
                actor=self.customer_actor,
                customer_id='cust-101',
                branch_id=1,
                fulfillment_warehouse_id=1,
                items=[CheckoutItemInput(product_id=2, quantity_milli=1000)],
                idempotency_key='idem-conflict-key-01',
                sqlite_conn=self.conn,
            )

        # Only the first order was created and stock deducted once (10000 - 1000 = 9000)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM orders_order").fetchone()[0], 1)
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            9000,
        )

    def test_08_valid_status_progression_and_invalid_status_transition_rejection(self):
        checkout_res = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[CheckoutItemInput(product_id=1, quantity_milli=1000)],
            idempotency_key='idem-lifecycle-01',
            sqlite_conn=self.conn,
        )
        order_id = checkout_res.order_id

        # Customer actor cannot advance management statuses
        with self.assertRaises(AuthenticationBoundaryError):
            transition_order_status(
                actor=self.customer_actor,
                order_id=order_id,
                to_status=ORDER_STATUS_CONFIRMED,
                sqlite_conn=self.conn,
            )

        # Cannot skip from PENDING directly to COMPLETED
        with self.assertRaises(InvalidOrderStatusTransitionError):
            transition_order_status(
                actor=self.owner_actor,
                order_id=order_id,
                to_status=ORDER_STATUS_COMPLETED,
                sqlite_conn=self.conn,
            )

        # Cannot use transition_order_status to transition to CANCELLED (must use cancel_order)
        with self.assertRaises(InvalidOrderStatusTransitionError):
            transition_order_status(
                actor=self.owner_actor,
                order_id=order_id,
                to_status=ORDER_STATUS_CANCELLED,
                sqlite_conn=self.conn,
            )

        # Valid progression: PENDING -> CONFIRMED -> PROCESSING -> COMPLETED
        t1 = transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_CONFIRMED,
            reason='تم تأكيد الطلب',
            sqlite_conn=self.conn,
        )
        self.assertEqual((t1.from_status, t1.to_status), (ORDER_STATUS_PENDING, ORDER_STATUS_CONFIRMED))

        t2 = transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_PROCESSING,
            reason='جاري تجهيز الطلب',
            sqlite_conn=self.conn,
        )
        self.assertEqual((t2.from_status, t2.to_status), (ORDER_STATUS_CONFIRMED, ORDER_STATUS_PROCESSING))

        t3 = transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_COMPLETED,
            reason='تم تسليم الطلب',
            sqlite_conn=self.conn,
        )
        self.assertEqual((t3.from_status, t3.to_status), (ORDER_STATUS_PROCESSING, ORDER_STATUS_COMPLETED))

        # Cannot transition backwards from COMPLETED to PROCESSING
        with self.assertRaises(InvalidOrderStatusTransitionError):
            transition_order_status(
                actor=self.owner_actor,
                order_id=order_id,
                to_status=ORDER_STATUS_PROCESSING,
                sqlite_conn=self.conn,
            )

        # Verify full OrderStatusHistory audit trail (4 entries: initial + 3 transitions)
        history_rows = self.conn.execute(
            """
            SELECT from_status, to_status, changed_by_actor_id
            FROM orders_orderstatushistory
            WHERE order_id = ?
            ORDER BY id ASC
            """,
            (order_id,),
        ).fetchall()
        self.assertEqual(
            history_rows,
            [
                (None, ORDER_STATUS_PENDING, 'cust-101'),
                (ORDER_STATUS_PENDING, ORDER_STATUS_CONFIRMED, 'owner-1'),
                (ORDER_STATUS_CONFIRMED, ORDER_STATUS_PROCESSING, 'owner-1'),
                (ORDER_STATUS_PROCESSING, ORDER_STATUS_COMPLETED, 'owner-1'),
            ],
        )

    def test_09_cancellation_restores_stock_once_and_records_movement_and_status_history(self):
        checkout_res = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[
                CheckoutItemInput(product_id=1, quantity_milli=1750),
                CheckoutItemInput(product_id=2, quantity_milli=2000),
            ],
            idempotency_key='idem-cancel-flow-checkout',
            sqlite_conn=self.conn,
        )
        order_id = checkout_res.order_id

        # Advance to CONFIRMED before cancelling
        transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_CONFIRMED,
            reason='تأكيد الطلب',
            sqlite_conn=self.conn,
        )

        # Another customer cannot cancel cust-101's order
        other_customer = ServiceActor(actor_id='cust-999', role=ActorRole.CUSTOMER.value, customer_id='cust-999')
        with self.assertRaises(AuthenticationBoundaryError):
            cancel_order(
                actor=other_customer,
                order_id=order_id,
                idempotency_key='idem-unauth-cancel',
                sqlite_conn=self.conn,
            )

        # Cancel order as authorized owner
        cancel_res = cancel_order(
            actor=self.owner_actor,
            order_id=order_id,
            idempotency_key='idem-cancel-flow-01',
            reason='إلغاء الطلب بناءً على طلب العميل',
            sqlite_conn=self.conn,
        )
        self.assertFalse(cancel_res.replayed)
        self.assertEqual(cancel_res.from_status, ORDER_STATUS_CONFIRMED)
        self.assertEqual(cancel_res.status, ORDER_STATUS_CANCELLED)
        self.assertTrue(cancel_res.cancellation_restored)
        self.assertEqual(cancel_res.restored_items, ((1, 1750), (2, 2000)))
        self.assertEqual(cancel_res.total_halalas, checkout_res.total_halalas)

        # Verify stock balances are restored back to original pre-checkout values (10000 and 5000)
        balances = self.conn.execute(
            """
            SELECT product_id, on_hand_milli, reserved_milli, version
            FROM products_warehousestockbalance
            WHERE warehouse_id = 1 AND product_id IN (1, 2)
            ORDER BY product_id ASC
            """
        ).fetchall()
        self.assertEqual(
            balances,
            [
                (1, 10000, 0, 3),
                (2, 5000, 1000, 3),
            ],
        )

        # Verify CANCELLATION_RESTORATION stock movements recorded with positive delta
        restoration_movements = self.conn.execute(
            """
            SELECT product_id, movement_type, quantity_delta_milli, balance_after_milli
            FROM products_stockmovement
            WHERE reference_order_id = ? AND movement_type = ?
            ORDER BY product_id ASC
            """,
            (order_id, MOVEMENT_TYPE_CANCELLATION_RESTORATION),
        ).fetchall()
        self.assertEqual(
            restoration_movements,
            [
                (1, MOVEMENT_TYPE_CANCELLATION_RESTORATION, 1750, 10000),
                (2, MOVEMENT_TYPE_CANCELLATION_RESTORATION, 2000, 5000),
            ],
        )

        # Verify OrderStatusHistory includes CONFIRMED -> CANCELLED
        last_status_hist = self.conn.execute(
            """
            SELECT from_status, to_status, changed_by_actor_id, reason
            FROM orders_orderstatushistory
            WHERE order_id = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            (order_id,),
        ).fetchone()
        self.assertEqual(
            last_status_hist,
            (ORDER_STATUS_CONFIRMED, ORDER_STATUS_CANCELLED, 'owner-1', 'إلغاء الطلب بناءً على طلب العميل'),
        )

    def test_10_repeated_cancellation_and_double_stock_restoration_prevention(self):
        checkout_res = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[CheckoutItemInput(product_id=1, quantity_milli=2500)],
            idempotency_key='idem-double-cancel-checkout',
            sqlite_conn=self.conn,
        )
        order_id = checkout_res.order_id

        # First cancellation restores stock from 7500 -> 10000
        first_cancel = cancel_order(
            actor=self.customer_actor,
            order_id=order_id,
            idempotency_key='idem-cancel-once-key',
            reason='إلغاء الطلب',
            sqlite_conn=self.conn,
        )
        self.assertFalse(first_cancel.replayed)
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            10000,
        )

        # 10a. Replay with the SAME idempotency_key and SAME order_id returns replayed=True without restoring twice
        replay_cancel = cancel_order(
            actor=self.customer_actor,
            order_id=order_id,
            idempotency_key='idem-cancel-once-key',
            reason='إلغاء الطلب',
            sqlite_conn=self.conn,
        )
        self.assertTrue(replay_cancel.replayed)
        self.assertEqual(replay_cancel.order_id, order_id)
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            10000,
        )

        # 10b. Reusing the same cancel idempotency_key for a DIFFERENT order_id raises IdempotencyConflictError
        with self.assertRaises(IdempotencyConflictError):
            cancel_order(
                actor=self.customer_actor,
                order_id=order_id + 999,
                idempotency_key='idem-cancel-once-key',
                sqlite_conn=self.conn,
            )

        # 10c. Attempting a second cancellation on the already-cancelled order with a NEW idempotency_key is rejected
        with self.assertRaises(OrderCancellationError):
            cancel_order(
                actor=self.customer_actor,
                order_id=order_id,
                idempotency_key='idem-cancel-second-key',
                reason='محاولة إلغاء مكررة',
                sqlite_conn=self.conn,
            )

        # Stock remains 10,000 and only 1 CANCELLATION_RESTORATION movement exists
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            10000,
        )
        self.assertEqual(
            self.conn.execute(
                "SELECT COUNT(*) FROM products_stockmovement WHERE reference_order_id = ? AND movement_type = ?",
                (order_id, MOVEMENT_TYPE_CANCELLATION_RESTORATION),
            ).fetchone()[0],
            1,
        )

    def test_11_cancellation_rejection_for_delivered_completed_order(self):
        checkout_res = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-101',
            branch_id=1,
            fulfillment_warehouse_id=1,
            items=[CheckoutItemInput(product_id=1, quantity_milli=3000)],
            idempotency_key='idem-completed-no-cancel',
            sqlite_conn=self.conn,
        )
        order_id = checkout_res.order_id

        transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_CONFIRMED,
            sqlite_conn=self.conn,
        )
        transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_PROCESSING,
            sqlite_conn=self.conn,
        )
        transition_order_status(
            actor=self.owner_actor,
            order_id=order_id,
            to_status=ORDER_STATUS_COMPLETED,
            sqlite_conn=self.conn,
        )

        # Attempting to cancel a COMPLETED (delivered) order must be rejected for both owner and customer
        with self.assertRaises(InvalidOrderStatusTransitionError):
            cancel_order(
                actor=self.owner_actor,
                order_id=order_id,
                idempotency_key='idem-cancel-completed-owner',
                sqlite_conn=self.conn,
            )
        with self.assertRaises(InvalidOrderStatusTransitionError):
            cancel_order(
                actor=self.customer_actor,
                order_id=order_id,
                idempotency_key='idem-cancel-completed-cust',
                sqlite_conn=self.conn,
            )

        # Verify order remains COMPLETED, cancellation_restored is 0, and stock remains 7,000
        order_row = self.conn.execute(
            "SELECT status, cancellation_restored FROM orders_order WHERE id = ?",
            (order_id,),
        ).fetchone()
        self.assertEqual(order_row, (ORDER_STATUS_COMPLETED, 0))
        self.assertEqual(
            self.conn.execute(
                "SELECT on_hand_milli FROM products_warehousestockbalance WHERE warehouse_id = 1 AND product_id = 1"
            ).fetchone()[0],
            7000,
        )


@unittest.skipUnless(
    DJANGO_AVAILABLE,
    "Django ORM runtime is not installed in the Android build container; requires Django + PostgreSQL/SQLite test runner.",
)
class OrdersDjangoModelAndServiceTests(DjangoTestCase):
    """
    Full Django ORM tests for Order, OrderItem, OrderStatusHistory, IdempotencyKey,
    and atomic `checkout_order`, `transition_order_status`, and `cancel_order` services.
    """

    def setUp(self):
        self.store = Store.objects.create(code='FRESH-KSA', name='فريش السعودية')
        self.branch = Branch.objects.create(store=self.store, code='RUH-01', name='فرع الرياض')
        self.warehouse = Warehouse.objects.create(
            branch=self.branch,
            code='WH-01',
            name='مستودع الرياض',
            is_primary_fulfillment=True,
        )
        self.category = Category.objects.create(name='خضروات', display_order=1)
        self.product = Product.objects.create(
            sku='SKU-CUCUMBER',
            name='خيار بلدي',
            category=self.category,
            price_halalas=650,
            unit='كجم',
            is_fractional_allowed=True,
        )
        self.balance = WarehouseStockBalance.objects.create(
            warehouse=self.warehouse,
            product=self.product,
            on_hand_milli=10000,
            reserved_milli=0,
        )
        self.customer_actor = ServiceActor(
            actor_id='cust-1',
            role=ActorRole.CUSTOMER.value,
            customer_id='cust-1',
        )
        self.owner_actor = ServiceActor(
            actor_id='owner-1',
            role=ActorRole.OWNER.value,
            authorized_branch_ids=frozenset({self.branch.id}),
        )

    def test_django_orm_checkout_and_single_execution_cancellation(self):
        res = checkout_order(
            actor=self.customer_actor,
            customer_id='cust-1',
            branch_id=self.branch.id,
            fulfillment_warehouse_id=self.warehouse.id,
            items=[CheckoutItemInput(product_id=self.product.id, quantity_milli=1500)],
            idempotency_key='django-idem-checkout-1',
        )
        self.assertEqual(res.total_halalas, 975)
        self.balance.refresh_from_db()
        self.assertEqual(self.balance.on_hand_milli, 8500)

        cancel_res = cancel_order(
            actor=self.owner_actor,
            order_id=res.order_id,
            idempotency_key='django-idem-cancel-1',
        )
        self.assertEqual(cancel_res.status, ORDER_STATUS_CANCELLED)
        self.balance.refresh_from_db()
        self.assertEqual(self.balance.on_hand_milli, 10000)

        with self.assertRaises(OrderCancellationError):
            cancel_order(
                actor=self.owner_actor,
                order_id=res.order_id,
                idempotency_key='django-idem-cancel-2',
            )

        # Verify database-level BEFORE UPDATE/DELETE triggers on orders_orderitem and orders_orderstatushistory
        from django.db import DatabaseError, connection

        item_id = res.items[0].id
        hist_id = OrderStatusHistory.objects.filter(order_id=res.order_id).first().id

        with self.assertRaises(DatabaseError):
            with transaction.atomic():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "UPDATE orders_orderitem SET unit_price_halalas = 1 WHERE id = %s",
                        [item_id],
                    )
        with self.assertRaises(DatabaseError):
            with transaction.atomic():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "DELETE FROM orders_orderitem WHERE id = %s",
                        [item_id],
                    )
        with self.assertRaises(DatabaseError):
            with transaction.atomic():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "UPDATE orders_orderstatushistory SET reason = 'tampered' WHERE id = %s",
                        [hist_id],
                    )
        with self.assertRaises(DatabaseError):
            with transaction.atomic():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "DELETE FROM orders_orderstatushistory WHERE id = %s",
                        [hist_id],
                    )


@unittest.skipUnless(
    DJANGO_AVAILABLE,
    "Django ORM runtime is required for DRF authentication and authorization tests.",
)
class AuthenticationAndAuthorizationDRFTests(DjangoTestCase):
    """
    Comprehensive tests for Micro Task 7B-3:
    - Cryptographic password hashing & plaintext password rejection.
    - Server-side mapping between authenticated Django Users and customer/staff identities.
    - Active user, active profile, active store, and active branch validation.
    - Zero-trust rejection of client-supplied role, actor_id, customer_id, store_id, or branch_id spoofing.
    - Customer isolation (own orders only, checkout allowed, status transition forbidden, cross-customer cancel forbidden).
    - Branch Manager isolation (assigned branch only; cross-branch view/transition/cancel forbidden).
    - Store Owner isolation (all active branches of own store; cross-store view/transition/cancel forbidden).
    """

    def setUp(self):
        from django.core.cache import cache
        from rest_framework.test import APIClient
        from apps.orders.auth import create_user_with_identity_profile
        from apps.orders.models import BranchRoleAssignment, UserIdentityProfile

        cache.clear()
        self.client_unauth = APIClient()

        # Store 1 with Branch 1 (Riyadh) and Branch 2 (Jeddah)
        self.store_1 = Store.objects.create(code='FRESH-KSA', name='فريش السعودية', is_active=True)
        self.branch_1 = Branch.objects.create(
            store=self.store_1,
            code='RUH-01',
            name='فرع الرياض',
            is_active=True,
        )
        self.branch_2 = Branch.objects.create(
            store=self.store_1,
            code='JED-01',
            name='فرع جدة',
            is_active=True,
        )
        self.wh_1 = Warehouse.objects.create(
            branch=self.branch_1,
            code='WH-RUH-01',
            name='مستودع الرياض',
            is_primary_fulfillment=True,
            is_active=True,
        )
        self.wh_2 = Warehouse.objects.create(
            branch=self.branch_2,
            code='WH-JED-01',
            name='مستودع جدة',
            is_primary_fulfillment=True,
            is_active=True,
        )

        # Store 2 with Branch 3 (Dammam)
        self.store_2 = Store.objects.create(code='FRESH-EAST', name='فريش الشرقية', is_active=True)
        self.branch_3 = Branch.objects.create(
            store=self.store_2,
            code='DMM-01',
            name='فرع الدمام',
            is_active=True,
        )
        self.wh_3 = Warehouse.objects.create(
            branch=self.branch_3,
            code='WH-DMM-01',
            name='مستودع الدمام',
            is_primary_fulfillment=True,
            is_active=True,
        )

        # Catalog & stock balances
        self.category = Category.objects.create(name='خضروات وفواكه', display_order=1, is_active=True)
        self.product_1 = Product.objects.create(
            sku='SKU-TOMATO-KG',
            name='طماطم محميات',
            category=self.category,
            price_halalas=800,
            unit='كجم',
            is_fractional_allowed=True,
            is_active=True,
        )
        self.bal_wh1 = WarehouseStockBalance.objects.create(
            warehouse=self.wh_1,
            product=self.product_1,
            on_hand_milli=20000,
            reserved_milli=0,
        )
        self.bal_wh2 = WarehouseStockBalance.objects.create(
            warehouse=self.wh_2,
            product=self.product_1,
            on_hand_milli=15000,
            reserved_milli=0,
        )
        self.bal_wh3 = WarehouseStockBalance.objects.create(
            warehouse=self.wh_3,
            product=self.product_1,
            on_hand_milli=10000,
            reserved_milli=0,
        )

        # Provision users & server-side identity profiles
        self.cust1_user, self.cust1_profile = create_user_with_identity_profile(
            username='customer_ahmed',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.CUSTOMER,
            customer_id='cust-ahmed-101',
        )
        self.cust2_user, self.cust2_profile = create_user_with_identity_profile(
            username='customer_sara',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.CUSTOMER,
            customer_id='cust-sara-202',
        )
        self.mgr_b1_user, self.mgr_b1_profile = create_user_with_identity_profile(
            username='manager_riyadh',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.BRANCH_MANAGER,
            store=self.store_1,
            branches=[self.branch_1],
        )
        self.mgr_b2_user, self.mgr_b2_profile = create_user_with_identity_profile(
            username='manager_jeddah',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.BRANCH_MANAGER,
            store=self.store_1,
            branches=[self.branch_2],
        )
        self.owner_s1_user, self.owner_s1_profile = create_user_with_identity_profile(
            username='owner_store1',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.OWNER,
            store=self.store_1,
        )
        self.owner_s2_user, self.owner_s2_profile = create_user_with_identity_profile(
            username='owner_store2',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.OWNER,
            store=self.store_2,
        )

    def _login_client(self, username: str, password: str = 'SafePassword#2026!'):
        from rest_framework.test import APIClient

        client = APIClient()
        res = client.post(
            '/api/v1/auth/login/',
            {'username': username, 'password': password},
            format='json',
        )
        self.assertEqual(res.status_code, 200, res.data)
        token = res.data['token']
        client.credentials(HTTP_AUTHORIZATION=f'Token {token}')
        return client, res.data

    def test_01_password_hashing_enforced_and_plaintext_rejected(self):
        from apps.orders.auth import assert_password_hashed, resolve_service_actor_from_user

        self.assertNotEqual(self.cust1_user.password, 'SafePassword#2026!')
        self.assertTrue(self.cust1_user.password.startswith('pbkdf2_sha256$'))
        assert_password_hashed(self.cust1_user)

        # Simulate plaintext password in user model and verify rejection
        self.cust1_user.password = 'plaintext_secret_password'
        with self.assertRaises(AuthenticationBoundaryError):
            assert_password_hashed(self.cust1_user)
        with self.assertRaises(AuthenticationBoundaryError):
            resolve_service_actor_from_user(self.cust1_user)

    def test_02_unauthenticated_requests_rejected_across_all_endpoints(self):
        self.assertEqual(self.client_unauth.get('/api/v1/orders/').status_code, 401)
        self.assertEqual(
            self.client_unauth.post(
                '/api/v1/orders/checkout/',
                {
                    'branch_id': self.branch_1.id,
                    'idempotency_key': 'unauth-1',
                    'items': [{'product_id': self.product_1.id, 'quantity_milli': 1000}],
                },
                format='json',
            ).status_code,
            401,
        )
        self.assertEqual(self.client_unauth.get('/api/v1/products/').status_code, 401)
        self.assertEqual(self.client_unauth.get('/api/v1/categories/').status_code, 401)
        self.assertEqual(self.client_unauth.get('/api/v1/auth/me/').status_code, 401)

    def test_03_login_identity_resolution_and_inactive_account_store_branch_rejection(self):
        # Valid customer login
        cust_client, cust_login = self._login_client('customer_ahmed')
        self.assertEqual(cust_login['role'], 'CUSTOMER')
        self.assertEqual(cust_login['customer_id'], 'cust-ahmed-101')
        self.assertIsNone(cust_login['authorized_branch_ids'])

        me_res = cust_client.get('/api/v1/auth/me/')
        self.assertEqual(me_res.status_code, 200)
        self.assertEqual(me_res.data['customer_id'], 'cust-ahmed-101')

        # Wrong password rejected with 401 and generic non-enumerating message
        bad_login = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'customer_ahmed', 'password': 'WrongPassword!'},
            format='json',
        )
        self.assertEqual(bad_login.status_code, 401)
        self.assertEqual(bad_login.data.get('detail'), 'Invalid authentication credentials.')

        # Deactivating profile immediately blocks existing token access with 403
        # and rejects login with 401 without leaking account state
        self.cust1_profile.is_active = False
        self.cust1_profile.save()
        self.assertEqual(cust_client.get('/api/v1/orders/').status_code, 403)
        inactive_profile_login = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'customer_ahmed', 'password': 'SafePassword#2026!'},
            format='json',
        )
        self.assertEqual(inactive_profile_login.status_code, 401)
        self.assertEqual(
            inactive_profile_login.data.get('detail'),
            'Invalid authentication credentials.',
        )
        self.cust1_profile.is_active = True
        self.cust1_profile.save()

        # Deactivating branch blocks branch manager whose only assigned branch is inactive
        mgr_client, mgr_login = self._login_client('manager_riyadh')
        self.assertEqual(mgr_login['authorized_branch_ids'], [self.branch_1.id])
        self.branch_1.is_active = False
        self.branch_1.save()
        self.assertEqual(mgr_client.get('/api/v1/orders/').status_code, 403)
        self.branch_1.is_active = True
        self.branch_1.save()

        # Deactivating store blocks store owner
        owner_client, owner_login = self._login_client('owner_store1')
        self.assertEqual(
            owner_login['authorized_branch_ids'],
            sorted([self.branch_1.id, self.branch_2.id]),
        )
        self.store_1.is_active = False
        self.store_1.save()
        self.assertEqual(owner_client.get('/api/v1/orders/').status_code, 403)
        self.store_1.is_active = True
        self.store_1.save()

    def test_04_client_supplied_role_customer_id_and_store_id_spoofing_rejected(self):
        cust1_client, _ = self._login_client('customer_ahmed')

        # Customer attempting to spoof role=OWNER in header or query
        self.assertEqual(
            cust1_client.get('/api/v1/orders/', HTTP_X_ACTOR_ROLE='OWNER').status_code,
            403,
        )
        self.assertEqual(
            cust1_client.get('/api/v1/orders/?role=OWNER').status_code,
            403,
        )

        # Customer attempting to spoof another customer_id in checkout payload or query
        spoof_checkout = cust1_client.post(
            '/api/v1/orders/checkout/',
            {
                'customer_id': 'cust-sara-202',
                'branch_id': self.branch_1.id,
                'idempotency_key': 'idem-spoof-cust-1',
                'items': [{'product_id': self.product_1.id, 'quantity_milli': 1000}],
            },
            format='json',
        )
        self.assertEqual(spoof_checkout.status_code, 403)
        self.assertEqual(
            cust1_client.get('/api/v1/orders/?customer_id=cust-sara-202').status_code,
            403,
        )

        # Branch manager attempting to spoof store_id of Store 2
        mgr1_client, _ = self._login_client('manager_riyadh')
        self.assertEqual(
            mgr1_client.get(f'/api/v1/orders/?store_id={self.store_2.id}').status_code,
            403,
        )

        # Cross-store BranchRoleAssignment rejected at model validation level
        from apps.orders.models import BranchRoleAssignment

        with self.assertRaises(ValidationError):
            BranchRoleAssignment.objects.create(
                profile=self.mgr_b1_profile,
                branch=self.branch_3,
            )

    def test_05_customer_checkout_isolation_and_role_branch_scoped_order_management(self):
        cust1_client, _ = self._login_client('customer_ahmed')
        cust2_client, _ = self._login_client('customer_sara')
        mgr1_client, _ = self._login_client('manager_riyadh')
        mgr2_client, _ = self._login_client('manager_jeddah')
        owner1_client, _ = self._login_client('owner_store1')
        owner2_client, _ = self._login_client('owner_store2')

        # 1. Customer 1 places an order in Branch 1 (1.500 kg * 800 halalas = 1200 halalas)
        # Client-submitted spoofed price_halalas=1 and total_halalas=1 are ignored
        checkout_res = cust1_client.post(
            '/api/v1/orders/checkout/',
            {
                'branch_id': self.branch_1.id,
                'idempotency_key': 'drf-idem-checkout-b1',
                'total_halalas': 1,
                'items': [
                    {
                        'product_id': self.product_1.id,
                        'quantity_milli': 1500,
                        'unit_price_halalas': 1,
                        'line_total_halalas': 1,
                    }
                ],
            },
            format='json',
        )
        self.assertEqual(checkout_res.status_code, 201, checkout_res.data)
        order_b1_id = checkout_res.data['id']
        self.assertEqual(checkout_res.data['customer_id'], 'cust-ahmed-101')
        self.assertEqual(checkout_res.data['total_halalas'], 1200)
        self.assertFalse(checkout_res.data['replayed'])

        # Idempotent replay returns 200 OK with replayed=True and does not deduct stock twice
        replay_res = cust1_client.post(
            '/api/v1/orders/checkout/',
            {
                'branch_id': self.branch_1.id,
                'idempotency_key': 'drf-idem-checkout-b1',
                'items': [{'product_id': self.product_1.id, 'quantity_milli': 1500}],
            },
            format='json',
        )
        self.assertEqual(replay_res.status_code, 200)
        self.assertTrue(replay_res.data['replayed'])
        self.assertEqual(replay_res.data['id'], order_b1_id)
        self.bal_wh1.refresh_from_db()
        self.assertEqual(self.bal_wh1.on_hand_milli, 18500)

        # 2. Customer 2 places an order in Branch 2
        checkout_b2 = cust2_client.post(
            '/api/v1/orders/checkout/',
            {
                'branch_id': self.branch_2.id,
                'idempotency_key': 'drf-idem-checkout-b2',
                'items': [{'product_id': self.product_1.id, 'quantity_milli': 2000}],
            },
            format='json',
        )
        self.assertEqual(checkout_b2.status_code, 201)
        order_b2_id = checkout_b2.data['id']

        # 3. Customer isolation: Customer 1 only sees order_b1; cannot retrieve or cancel order_b2
        cust1_list = cust1_client.get('/api/v1/orders/')
        self.assertEqual([o['id'] for o in cust1_list.data], [order_b1_id])
        self.assertEqual(cust1_client.get(f'/api/v1/orders/{order_b2_id}/').status_code, 403)
        self.assertEqual(
            cust1_client.post(
                f'/api/v1/orders/{order_b2_id}/cancel/',
                {'idempotency_key': 'cust1-illegal-cancel'},
                format='json',
            ).status_code,
            403,
        )

        # Customer 1 cannot transition order status even on their own order
        self.assertEqual(
            cust1_client.post(
                f'/api/v1/orders/{order_b1_id}/transition-status/',
                {'to_status': 'CONFIRMED'},
                format='json',
            ).status_code,
            403,
        )

        # 4. Branch Manager isolation:
        # Manager 1 (Branch 1) sees only order_b1; cannot access/transition/cancel order_b2 (Branch 2)
        mgr1_list = mgr1_client.get('/api/v1/orders/')
        self.assertEqual([o['id'] for o in mgr1_list.data], [order_b1_id])
        self.assertEqual(mgr1_client.get(f'/api/v1/orders/?branch_id={self.branch_2.id}').status_code, 403)
        self.assertEqual(mgr1_client.get(f'/api/v1/orders/{order_b2_id}/').status_code, 403)
        self.assertEqual(
            mgr1_client.post(
                f'/api/v1/orders/{order_b2_id}/transition-status/',
                {'to_status': 'CONFIRMED'},
                format='json',
            ).status_code,
            403,
        )
        self.assertEqual(
            mgr1_client.post(
                f'/api/v1/orders/{order_b2_id}/cancel/',
                {'idempotency_key': 'mgr1-cross-branch-cancel'},
                format='json',
            ).status_code,
            403,
        )

        # Manager 1 CAN transition order_b1 (Branch 1) to CONFIRMED
        mgr1_confirm = mgr1_client.post(
            f'/api/v1/orders/{order_b1_id}/transition-status/',
            {'to_status': 'CONFIRMED', 'reason': 'تم تأكيد الطلب في فرع الرياض'},
            format='json',
        )
        self.assertEqual(mgr1_confirm.status_code, 200)
        self.assertEqual(mgr1_confirm.data['status'], 'CONFIRMED')

        # 5. Store Owner isolation:
        # Owner 1 (Store 1) sees both order_b1 and order_b2, and can manage both
        owner1_list = owner1_client.get('/api/v1/orders/')
        self.assertEqual(set(o['id'] for o in owner1_list.data), {order_b1_id, order_b2_id})

        # Owner 2 (Store 2) sees 0 orders from Store 1 and gets 403 on Store 1 orders
        owner2_list = owner2_client.get('/api/v1/orders/')
        self.assertEqual(owner2_list.data, [])
        self.assertEqual(owner2_client.get(f'/api/v1/orders/{order_b1_id}/').status_code, 403)
        self.assertEqual(
            owner2_client.post(
                f'/api/v1/orders/{order_b1_id}/transition-status/',
                {'to_status': 'PROCESSING'},
                format='json',
            ).status_code,
            403,
        )
        self.assertEqual(
            owner2_client.post(
                f'/api/v1/orders/{order_b1_id}/cancel/',
                {'idempotency_key': 'owner2-cross-store-cancel'},
                format='json',
            ).status_code,
            403,
        )

        # 6. Cancellation via HTTP endpoint restores stock once and blocks repeated cancellation
        cancel_b1 = cust1_client.post(
            f'/api/v1/orders/{order_b1_id}/cancel/',
            {'idempotency_key': 'drf-cancel-b1-once', 'reason': 'إلغاء من العميل'},
            format='json',
        )
        self.assertEqual(cancel_b1.status_code, 200)
        self.assertEqual(cancel_b1.data['status'], 'CANCELLED')
        self.assertFalse(cancel_b1.data['replayed'])
        self.bal_wh1.refresh_from_db()
        self.assertEqual(self.bal_wh1.on_hand_milli, 20000)

        # Replay with same idempotency_key returns 200 with replayed=True
        cancel_b1_replay = cust1_client.post(
            f'/api/v1/orders/{order_b1_id}/cancel/',
            {'idempotency_key': 'drf-cancel-b1-once', 'reason': 'إلغاء من العميل'},
            format='json',
        )
        self.assertEqual(cancel_b1_replay.status_code, 200)
        self.assertTrue(cancel_b1_replay.data['replayed'])
        self.bal_wh1.refresh_from_db()
        self.assertEqual(self.bal_wh1.on_hand_milli, 20000)

        # Second cancel with a new idempotency_key returns 409 Conflict
        cancel_b1_duplicate = cust1_client.post(
            f'/api/v1/orders/{order_b1_id}/cancel/',
            {'idempotency_key': 'drf-cancel-b1-second'},
            format='json',
        )
        self.assertEqual(cancel_b1_duplicate.status_code, 409)
        self.bal_wh1.refresh_from_db()
        self.assertEqual(self.bal_wh1.on_hand_milli, 20000)

        # 7. Progress order_b2 to COMPLETED and verify cancellation is rejected with 400
        self.assertEqual(
            mgr2_client.post(
                f'/api/v1/orders/{order_b2_id}/transition-status/',
                {'to_status': 'CONFIRMED'},
                format='json',
            ).status_code,
            200,
        )
        self.assertEqual(
            mgr2_client.post(
                f'/api/v1/orders/{order_b2_id}/transition-status/',
                {'to_status': 'PROCESSING'},
                format='json',
            ).status_code,
            200,
        )
        self.assertEqual(
            owner1_client.post(
                f'/api/v1/orders/{order_b2_id}/transition-status/',
                {'to_status': 'COMPLETED'},
                format='json',
            ).status_code,
            200,
        )
        completed_cancel = owner1_client.post(
            f'/api/v1/orders/{order_b2_id}/cancel/',
            {'idempotency_key': 'drf-cancel-completed-b2'},
            format='json',
        )
        self.assertEqual(completed_cancel.status_code, 400)
        self.bal_wh2.refresh_from_db()
        self.assertEqual(self.bal_wh2.on_hand_milli, 13000)


@unittest.skipUnless(
    DJANGO_AVAILABLE,
    "Django ORM runtime is required for token lifecycle and rate limiting tests.",
)
class TokenLifecycleAndRateLimitingDRFTests(DjangoTestCase):
    """
    Comprehensive tests for Micro Task 7B-4:
    1. Valid credentials produce a usable authentication credential (hashed in DB, with explicit expiry).
    2. Invalid credentials and inactive accounts are rejected without account-enumeration details.
    3. Expired and revoked tokens cannot access protected endpoints.
    4. Renewal/rotation follows the documented single-use policy and detects refresh token replay/reuse.
    5. Logout invalidates both access and refresh credentials immediately.
    6. Login and token-refresh rate limiting / throttling activates after repeated attempts.
    """

    def setUp(self):
        from django.core.cache import cache
        from rest_framework.test import APIClient
        from apps.orders.auth import create_user_with_identity_profile
        from apps.orders.models import UserIdentityProfile

        cache.clear()
        self.client_unauth = APIClient()
        self.store = Store.objects.create(code='FRESH-KSA', name='فريش السعودية', is_active=True)
        self.branch = Branch.objects.create(
            store=self.store,
            code='RUH-01',
            name='فرع الرياض',
            is_active=True,
        )
        self.warehouse = Warehouse.objects.create(
            branch=self.branch,
            code='WH-RUH-01',
            name='مستودع الرياض',
            is_primary_fulfillment=True,
            is_active=True,
        )
        self.category = Category.objects.create(name='خضروات', display_order=1, is_active=True)
        self.product = Product.objects.create(
            sku='SKU-CUCUMBER',
            name='خيار بلدي',
            category=self.category,
            price_halalas=650,
            unit='كجم',
            is_fractional_allowed=True,
            is_active=True,
        )
        WarehouseStockBalance.objects.create(
            warehouse=self.warehouse,
            product=self.product,
            on_hand_milli=10000,
            reserved_milli=0,
        )

        self.customer_user, self.customer_profile = create_user_with_identity_profile(
            username='lifecycle_customer',
            password='ValidPassword#2026!',
            role=UserIdentityProfile.Role.CUSTOMER,
            customer_id='cust-lifecycle-1',
        )
        self.inactive_user, _ = create_user_with_identity_profile(
            username='inactive_django_user',
            password='ValidPassword#2026!',
            role=UserIdentityProfile.Role.CUSTOMER,
            customer_id='cust-inactive-user',
            is_active=False,
        )
        self.inactive_profile_user, _ = create_user_with_identity_profile(
            username='inactive_profile_user',
            password='ValidPassword#2026!',
            role=UserIdentityProfile.Role.CUSTOMER,
            customer_id='cust-inactive-prof',
            is_active=True,
            profile_is_active=False,
        )

    def test_01_valid_credentials_produce_usable_hashed_token_pair_with_explicit_expiry(self):
        from rest_framework.test import APIClient
        from apps.orders.auth import hash_opaque_token
        from apps.orders.models import AuthSessionToken

        res = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        self.assertEqual(res.status_code, 200, res.data)
        access_token = res.data['access_token']
        refresh_token = res.data['refresh_token']
        self.assertEqual(res.data['token'], access_token)
        self.assertNotEqual(access_token, refresh_token)
        self.assertGreater(res.data['expires_in'], 0)
        self.assertGreaterEqual(res.data['refresh_expires_in'], res.data['expires_in'])
        self.assertIn('expires_at', res.data)
        self.assertIn('refresh_expires_at', res.data)

        # Verify raw tokens are never stored in plaintext in the database
        self.assertFalse(
            AuthSessionToken.objects.filter(access_token_hash=access_token).exists()
        )
        self.assertFalse(
            AuthSessionToken.objects.filter(refresh_token_hash=refresh_token).exists()
        )
        session_record = AuthSessionToken.objects.get(
            access_token_hash=hash_opaque_token(access_token)
        )
        self.assertEqual(session_record.refresh_token_hash, hash_opaque_token(refresh_token))
        self.assertIsNone(session_record.revoked_at)

        # Usable with both `Token <key>` and `Bearer <key>` schemes
        token_client = APIClient()
        token_client.credentials(HTTP_AUTHORIZATION=f'Token {access_token}')
        self.assertEqual(token_client.get('/api/v1/auth/me/').status_code, 200)

        bearer_client = APIClient()
        bearer_client.credentials(HTTP_AUTHORIZATION=f'Bearer {access_token}')
        self.assertEqual(bearer_client.get('/api/v1/auth/me/').status_code, 200)

    def test_02_invalid_credentials_rejected_without_account_enumeration_details(self):
        attempts = [
            # Non-existent account
            {'username': 'nonexistent_account_xyz', 'password': 'AnyPassword#2026!'},
            # Existing active account with wrong password
            {'username': 'lifecycle_customer', 'password': 'WrongPassword#2026!'},
            # Existing inactive Django User with wrong password
            {'username': 'inactive_django_user', 'password': 'WrongPassword#2026!'},
            # Existing inactive Django User with valid password
            {'username': 'inactive_django_user', 'password': 'ValidPassword#2026!'},
            # Existing inactive UserIdentityProfile with valid password
            {'username': 'inactive_profile_user', 'password': 'ValidPassword#2026!'},
        ]

        from apps.orders.models import AuthThrottleEvent

        for payload in attempts:
            AuthThrottleEvent.objects.all().delete()
            response = self.client_unauth.post('/api/v1/auth/login/', payload, format='json')
            self.assertEqual(response.status_code, 401, payload)
            self.assertEqual(
                response.data,
                {'detail': 'Invalid authentication credentials.'},
            )
            self.assertNotIn(payload['username'], str(response.data))

    def test_03_expired_and_revoked_tokens_cannot_access_protected_endpoints(self):
        from datetime import timedelta
        from django.utils import timezone
        from rest_framework.test import APIClient
        from apps.orders.auth import hash_opaque_token
        from apps.orders.models import AuthSessionToken

        # 1. Expired access token cannot access protected endpoints
        res1 = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        expired_access = res1.data['access_token']
        token_obj_1 = AuthSessionToken.objects.get(
            access_token_hash=hash_opaque_token(expired_access)
        )
        token_obj_1.access_expires_at = timezone.now() - timedelta(seconds=5)
        token_obj_1.save(update_fields=['access_expires_at'])

        expired_client = APIClient()
        expired_client.credentials(HTTP_AUTHORIZATION=f'Token {expired_access}')
        self.assertEqual(expired_client.get('/api/v1/auth/me/').status_code, 401)
        self.assertEqual(expired_client.get('/api/v1/orders/').status_code, 401)
        self.assertEqual(expired_client.get('/api/v1/products/').status_code, 401)
        token_obj_1.refresh_from_db()
        self.assertIsNotNone(token_obj_1.revoked_at)
        self.assertEqual(token_obj_1.revocation_reason, AuthSessionToken.RevocationReason.EXPIRED)

        # 2. Explicitly revoked access token cannot access protected endpoints
        res2 = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        revoked_access = res2.data['access_token']
        AuthSessionToken.objects.filter(
            access_token_hash=hash_opaque_token(revoked_access)
        ).update(
            revoked_at=timezone.now(),
            revocation_reason=AuthSessionToken.RevocationReason.REVOKED,
        )

        revoked_client = APIClient()
        revoked_client.credentials(HTTP_AUTHORIZATION=f'Token {revoked_access}')
        self.assertEqual(revoked_client.get('/api/v1/auth/me/').status_code, 401)
        self.assertEqual(revoked_client.get('/api/v1/orders/').status_code, 401)

    def test_04_token_renewal_rotation_policy_and_replay_reuse_detection(self):
        from datetime import timedelta
        from django.utils import timezone
        from rest_framework.test import APIClient
        from apps.orders.auth import hash_opaque_token
        from apps.orders.models import AuthSessionToken

        login_res = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        self.assertEqual(login_res.status_code, 200)
        access_1 = login_res.data['access_token']
        refresh_1 = login_res.data['refresh_token']

        # Rotate refresh_1 -> produces access_2 and refresh_2
        refresh_res = self.client_unauth.post(
            '/api/v1/auth/refresh/',
            {'refresh_token': refresh_1},
            format='json',
        )
        self.assertEqual(refresh_res.status_code, 200, refresh_res.data)
        access_2 = refresh_res.data['access_token']
        refresh_2 = refresh_res.data['refresh_token']
        self.assertNotEqual(access_2, access_1)
        self.assertNotEqual(refresh_2, refresh_1)

        # Old access_1 is immediately revoked on rotation and cannot access protected endpoints
        old_client = APIClient()
        old_client.credentials(HTTP_AUTHORIZATION=f'Token {access_1}')
        self.assertEqual(old_client.get('/api/v1/auth/me/').status_code, 401)
        self.assertEqual(old_client.get('/api/v1/orders/').status_code, 401)

        # New access_2 is active and works on protected endpoints
        new_client = APIClient()
        new_client.credentials(HTTP_AUTHORIZATION=f'Token {access_2}')
        self.assertEqual(new_client.get('/api/v1/auth/me/').status_code, 200)
        self.assertEqual(new_client.get('/api/v1/orders/').status_code, 200)

        # Replaying already-rotated refresh_1 is rejected with 401 AND revokes the entire token family (including access_2/refresh_2)
        replay_res = self.client_unauth.post(
            '/api/v1/auth/refresh/',
            {'refresh_token': refresh_1},
            format='json',
        )
        self.assertEqual(replay_res.status_code, 401)

        token_2_record = AuthSessionToken.objects.get(
            access_token_hash=hash_opaque_token(access_2)
        )
        self.assertIsNotNone(token_2_record.revoked_at)
        self.assertEqual(
            token_2_record.revocation_reason,
            AuthSessionToken.RevocationReason.REUSE_DETECTED,
        )
        self.assertEqual(new_client.get('/api/v1/auth/me/').status_code, 401)
        self.assertEqual(
            self.client_unauth.post(
                '/api/v1/auth/refresh/',
                {'refresh_token': refresh_2},
                format='json',
            ).status_code,
            401,
        )

        # Expired refresh token is rejected with 401
        login_res_2 = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        refresh_exp = login_res_2.data['refresh_token']
        AuthSessionToken.objects.filter(
            refresh_token_hash=hash_opaque_token(refresh_exp)
        ).update(
            access_expires_at=timezone.now() - timedelta(seconds=60),
            refresh_expires_at=timezone.now() - timedelta(seconds=5),
        )
        self.assertEqual(
            self.client_unauth.post(
                '/api/v1/auth/refresh/',
                {'refresh_token': refresh_exp},
                format='json',
            ).status_code,
            401,
        )

    def test_05_logout_invalidates_access_and_refresh_credentials_immediately(self):
        from rest_framework.test import APIClient

        login_res = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        access_token = login_res.data['access_token']
        refresh_token = login_res.data['refresh_token']

        auth_client = APIClient()
        auth_client.credentials(HTTP_AUTHORIZATION=f'Token {access_token}')
        self.assertEqual(auth_client.get('/api/v1/auth/me/').status_code, 200)

        # Logout invalidates both access_token and refresh_token
        logout_res = auth_client.post('/api/v1/auth/logout/', {}, format='json')
        self.assertEqual(logout_res.status_code, 200)

        self.assertEqual(auth_client.get('/api/v1/auth/me/').status_code, 401)
        self.assertEqual(auth_client.get('/api/v1/orders/').status_code, 401)
        self.assertEqual(auth_client.post('/api/v1/auth/logout/').status_code, 401)
        self.assertEqual(
            self.client_unauth.post(
                '/api/v1/auth/refresh/',
                {'refresh_token': refresh_token},
                format='json',
            ).status_code,
            401,
        )

    def test_06_login_and_refresh_throttling_activates_after_repeated_attempts(self):
        from django.test import override_settings
        from apps.orders.models import AuthThrottleEvent

        # 1. Repeated failed login attempts trigger 429 Too Many Requests
        AuthThrottleEvent.objects.all().delete()
        with override_settings(
            AUTH_MAX_FAILED_LOGIN_ATTEMPTS=3,
            AUTH_FAILED_LOGIN_WINDOW_SECONDS=300,
            AUTH_LOGIN_RATE_LIMIT_REQUESTS=20,
            AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS=60,
        ):
            for _ in range(3):
                bad_res = self.client_unauth.post(
                    '/api/v1/auth/login/',
                    {'username': 'lifecycle_customer', 'password': 'WrongPassword!'},
                    format='json',
                )
                self.assertEqual(bad_res.status_code, 401)

            # 4th attempt is throttled with 429 even when supplying the valid password
            throttled_login = self.client_unauth.post(
                '/api/v1/auth/login/',
                {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
                format='json',
            )
            self.assertEqual(throttled_login.status_code, 429)
            self.assertIn('Retry-After', throttled_login.headers)

        # 2. Token refresh endpoint rate limiting triggers 429 Too Many Requests after repeated requests
        AuthThrottleEvent.objects.all().delete()
        with override_settings(
            AUTH_REFRESH_RATE_LIMIT_REQUESTS=3,
            AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS=60,
        ):
            for _ in range(3):
                r = self.client_unauth.post(
                    '/api/v1/auth/refresh/',
                    {'refresh_token': 'invalid-refresh-token'},
                    format='json',
                )
                self.assertEqual(r.status_code, 401)

            throttled_refresh = self.client_unauth.post(
                '/api/v1/auth/refresh/',
                {'refresh_token': 'invalid-refresh-token'},
                format='json',
            )
            self.assertEqual(throttled_refresh.status_code, 429)
            self.assertIn('Retry-After', throttled_refresh.headers)

    def test_07_jwt_allowlist_blocklist_rotation_and_immediate_session_invalidation(self):
        from django.contrib.sessions.models import Session
        from django.core.cache import cache
        from rest_framework.test import APIClient
        from apps.orders.auth import (
            CACHE_PREFIX_USER_SESSION,
            decode_and_verify_jwt,
            is_token_allowlisted,
            is_token_blocklisted,
        )

        # 1. Login issues signed HS256 JWTs for both access_token and refresh_token
        login_res = self.client_unauth.post(
            '/api/v1/auth/login/',
            {'username': 'lifecycle_customer', 'password': 'ValidPassword#2026!'},
            format='json',
        )
        self.assertEqual(login_res.status_code, 200)
        access_1 = login_res.data['access_token']
        refresh_1 = login_res.data['refresh_token']

        # Both tokens are 3-segment signed JWTs with expected claims
        self.assertEqual(access_1.count('.'), 2)
        self.assertEqual(refresh_1.count('.'), 2)
        access_claims_1 = decode_and_verify_jwt(access_1, expected_type='access')
        refresh_claims_1 = decode_and_verify_jwt(refresh_1, expected_type='refresh')
        self.assertEqual(access_claims_1['user_id'], self.customer_user.pk)
        self.assertEqual(refresh_claims_1['user_id'], self.customer_user.pk)
        self.assertEqual(access_claims_1['family_id'], refresh_claims_1['family_id'])
        self.assertEqual(access_claims_1['pair_jti'], refresh_claims_1['pair_jti'])

        # Both tokens are in the allowlist and not in the blocklist
        self.assertTrue(is_token_allowlisted(access_1, expected_type='access'))
        self.assertTrue(is_token_allowlisted(refresh_1, expected_type='refresh'))
        self.assertFalse(is_token_blocklisted(access_1))
        self.assertFalse(is_token_blocklisted(refresh_1))

        # Tampered JWT signature or wrong token type (refresh token used as access token) is rejected
        tampered_access = access_1[:-4] + ('AAAA' if access_1[-4:] != 'AAAA' else 'BBBB')
        bad_client = APIClient()
        bad_client.credentials(HTTP_AUTHORIZATION=f'Bearer {tampered_access}')
        self.assertEqual(bad_client.get('/api/v1/auth/me/').status_code, 401)

        refresh_as_access_client = APIClient()
        refresh_as_access_client.credentials(HTTP_AUTHORIZATION=f'Bearer {refresh_1}')
        self.assertEqual(refresh_as_access_client.get('/api/v1/auth/me/').status_code, 401)

        # Create a server-side Django session for the user to verify session data invalidation on rotation
        session_client = APIClient()
        session_client.force_login(self.customer_user)
        self.assertTrue(
            Session.objects.filter(
                session_key=session_client.session.session_key
            ).exists()
        )
        old_session_key = session_client.session.session_key

        # 2. Rotate refresh_1 -> immediately blocklists access_1 and refresh_1, removes from allowlist,
        # invalidates the user's prior Django session data, and allowlists access_2 and refresh_2
        rot_res = self.client_unauth.post(
            '/api/v1/auth/refresh/',
            {'refresh_token': refresh_1},
            format='json',
        )
        self.assertEqual(rot_res.status_code, 200)
        access_2 = rot_res.data['access_token']
        refresh_2 = rot_res.data['refresh_token']

        self.assertTrue(is_token_blocklisted(access_1))
        self.assertTrue(is_token_blocklisted(refresh_1))
        self.assertFalse(is_token_allowlisted(access_1, expected_type='access'))
        self.assertFalse(is_token_allowlisted(refresh_1, expected_type='refresh'))

        self.assertFalse(is_token_blocklisted(access_2))
        self.assertFalse(is_token_blocklisted(refresh_2))
        self.assertTrue(is_token_allowlisted(access_2, expected_type='access'))
        self.assertTrue(is_token_allowlisted(refresh_2, expected_type='refresh'))

        # Prior Django session row was immediately invalidated on token rotation
        self.assertFalse(Session.objects.filter(session_key=old_session_key).exists())

        # 3. Create another Django session and perform logout with access_2:
        # verifies immediate blocklisting of access_2 and refresh_2 and session data invalidation
        session_client_2 = APIClient()
        session_client_2.force_login(self.customer_user)
        session_key_2 = session_client_2.session.session_key
        self.assertTrue(Session.objects.filter(session_key=session_key_2).exists())

        auth_client_2 = APIClient()
        auth_client_2.credentials(HTTP_AUTHORIZATION=f'Bearer {access_2}')
        logout_res = auth_client_2.post('/api/v1/auth/logout/', {}, format='json')
        self.assertEqual(logout_res.status_code, 200)

        self.assertTrue(is_token_blocklisted(access_2))
        self.assertTrue(is_token_blocklisted(refresh_2))
        self.assertFalse(is_token_allowlisted(access_2, expected_type='access'))
        self.assertFalse(is_token_allowlisted(refresh_2, expected_type='refresh'))
        self.assertIsNone(cache.get(f"{CACHE_PREFIX_USER_SESSION}{self.customer_user.pk}"))
        self.assertFalse(Session.objects.filter(session_key=session_key_2).exists())

    def test_08_cache_based_rate_limit_middleware_and_drf_decorator_by_ip_and_user(self):
        import json
        from django.core.cache import cache
        from django.http import HttpResponse
        from django.test import RequestFactory, override_settings
        from rest_framework.decorators import api_view, permission_classes
        from rest_framework.permissions import AllowAny
        from rest_framework.response import Response
        from apps.orders.auth import (
            AuthRateLimitMiddleware,
            CACHE_PREFIX_RL_LOGIN_FAIL_IP,
            CACHE_PREFIX_RL_LOGIN_FAIL_USER,
            CACHE_PREFIX_RL_LOGIN_REQ_IP,
            CACHE_PREFIX_RL_LOGIN_REQ_USER,
            enforce_auth_rate_limit,
            record_failed_login_attempt,
        )
        from apps.orders.models import AuthThrottleEvent

        # 1. Verify AUTH_LOGIN_RATE_LIMIT_REQUESTS enforced via AuthRateLimitMiddleware using cache
        cache.clear()
        AuthThrottleEvent.objects.all().delete()
        rf = RequestFactory()
        middleware = AuthRateLimitMiddleware(lambda req: HttpResponse('OK', status=200))

        with override_settings(
            AUTH_LOGIN_RATE_LIMIT_REQUESTS=2,
            AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS=60,
            AUTH_MAX_FAILED_LOGIN_ATTEMPTS=10,
        ):
            for _ in range(2):
                req = rf.post(
                    '/api/v1/auth/login/',
                    data=json.dumps({'username': 'lifecycle_customer', 'password': 'x'}),
                    content_type='application/json',
                    REMOTE_ADDR='203.0.113.10',
                )
                resp = middleware(req)
                self.assertEqual(resp.status_code, 200)

            # Verify counts are tracked in django.core.cache by both IP and User Identifier
            self.assertEqual(
                len(cache.get(f"{CACHE_PREFIX_RL_LOGIN_REQ_IP}203.0.113.10") or []),
                2,
            )
            self.assertEqual(
                len(cache.get(f"{CACHE_PREFIX_RL_LOGIN_REQ_USER}lifecycle_customer") or []),
                2,
            )

            # 3rd request from the same IP is blocked by AuthRateLimitMiddleware with 429
            req_blocked = rf.post(
                '/api/v1/auth/login/',
                data=json.dumps({'username': 'another_user', 'password': 'x'}),
                content_type='application/json',
                REMOTE_ADDR='203.0.113.10',
            )
            resp_blocked = middleware(req_blocked)
            self.assertEqual(resp_blocked.status_code, 429)
            self.assertIn('Retry-After', resp_blocked)

            # 3rd request for the same User Identifier from a DIFFERENT IP is also blocked with 429
            req_user_blocked = rf.post(
                '/api/v1/auth/login/',
                data=json.dumps({'username': 'lifecycle_customer', 'password': 'x'}),
                content_type='application/json',
                REMOTE_ADDR='203.0.113.99',
            )
            resp_user_blocked = middleware(req_user_blocked)
            self.assertEqual(resp_user_blocked.status_code, 429)

        # 2. Verify AUTH_MAX_FAILED_LOGIN_ATTEMPTS enforced via @enforce_auth_rate_limit DRF decorator
        cache.clear()
        AuthThrottleEvent.objects.all().delete()

        @api_view(['POST'])
        @permission_classes([AllowAny])
        @enforce_auth_rate_limit('login')
        def dummy_drf_login_view(request):
            record_failed_login_attempt(request, request.data.get('username'))
            return Response({'detail': 'failed'}, status=401)

        with override_settings(
            AUTH_MAX_FAILED_LOGIN_ATTEMPTS=2,
            AUTH_FAILED_LOGIN_WINDOW_SECONDS=300,
            AUTH_LOGIN_RATE_LIMIT_REQUESTS=50,
            AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS=60,
        ):
            for i in range(2):
                req = rf.post(
                    '/api/v1/auth/login/',
                    data=json.dumps({'username': 'target_user', 'password': 'bad'}),
                    content_type='application/json',
                    REMOTE_ADDR=f'198.51.100.{10 + i}',
                )
                drf_resp = dummy_drf_login_view(req)
                self.assertEqual(drf_resp.status_code, 401)

            self.assertEqual(
                len(cache.get(f"{CACHE_PREFIX_RL_LOGIN_FAIL_USER}target_user") or []),
                2,
            )
            self.assertEqual(
                len(cache.get(f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}198.51.100.10") or []),
                1,
            )

            # 3rd attempt for 'target_user' even from a new IP is throttled by the decorator with 429
            req_throttled = rf.post(
                '/api/v1/auth/login/',
                data=json.dumps({'username': 'target_user', 'password': 'bad'}),
                content_type='application/json',
                REMOTE_ADDR='198.51.100.99',
            )
            throttled_resp = dummy_drf_login_view(req_throttled)
            self.assertEqual(throttled_resp.status_code, 429)
            self.assertIn('Retry-After', throttled_resp.headers)

    def test_09_custom_middleware_tracks_and_enforces_failed_logins_by_client_ip_in_cache(self):
        import json
        from django.core.cache import cache
        from django.http import HttpResponse
        from django.test import RequestFactory, override_settings
        from django.utils import timezone
        from apps.orders.auth import (
            AuthRateLimitMiddleware,
            CACHE_PREFIX_RL_LOGIN_FAIL_IP,
            get_client_ip_failed_login_count,
        )
        from apps.orders.middleware import FailedLoginAttemptMiddleware
        from apps.orders.models import AuthThrottleEvent

        self.assertIs(FailedLoginAttemptMiddleware, AuthRateLimitMiddleware)
        cache.clear()
        AuthThrottleEvent.objects.all().delete()
        rf = RequestFactory()

        # Simulate a plain login endpoint wrapped only by FailedLoginAttemptMiddleware (returning 401 on bad password, 200 on valid)
        def login_handler(req):
            body = json.loads(req.body.decode('utf-8'))
            if body.get('password') == 'ValidPass!':
                return HttpResponse('Authenticated', status=200)
            return HttpResponse('Unauthorized', status=401)

        middleware = FailedLoginAttemptMiddleware(login_handler)

        with override_settings(
            AUTH_MAX_FAILED_LOGIN_ATTEMPTS=3,
            AUTH_FAILED_LOGIN_WINDOW_SECONDS=300,
            AUTH_LOGIN_RATE_LIMIT_REQUESTS=50,
            AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS=60,
        ):
            # 1. Middleware automatically tracks failed login responses (401) per client IP across all login endpoints
            login_paths = ['/api/v1/auth/login/', '/api/v1/auth/token/', '/api/v1/auth/login/']
            for idx, path in enumerate(login_paths):
                req = rf.post(
                    path,
                    data=json.dumps({'username': f'user_{idx}', 'password': 'WrongPass!'}),
                    content_type='application/json',
                    REMOTE_ADDR='192.0.2.55',
                )
                resp = middleware(req)
                self.assertEqual(resp.status_code, 401)
                self.assertEqual(get_client_ip_failed_login_count('192.0.2.55'), idx + 1)

            locked, retry_after = FailedLoginAttemptMiddleware.is_ip_locked_out('192.0.2.55')
            self.assertTrue(locked)
            self.assertGreater(retry_after, 0)

            # 2. 4th login attempt from the same client IP ('192.0.2.55') is blocked with 429 on both login endpoints
            for path in ('/api/v1/auth/login/', '/api/v1/auth/token/'):
                blocked_req = rf.post(
                    path,
                    data=json.dumps({'username': 'new_user', 'password': 'ValidPass!'}),
                    content_type='application/json',
                    REMOTE_ADDR='192.0.2.55',
                )
                blocked_resp = middleware(blocked_req)
                self.assertEqual(blocked_resp.status_code, 429)
                self.assertIn('Retry-After', blocked_resp)

            # 3. Verify AUTH_FAILED_LOGIN_WINDOW_SECONDS sliding window expiration:
            # When cached timestamps for '192.0.2.55' are older than AUTH_FAILED_LOGIN_WINDOW_SECONDS (300s),
            # the middleware prunes them and allows login again
            expired_ts = timezone.now().timestamp() - 305.0
            cache.set(
                f"{CACHE_PREFIX_RL_LOGIN_FAIL_IP}192.0.2.55",
                [expired_ts, expired_ts + 1.0, expired_ts + 2.0],
                timeout=300,
            )
            self.assertEqual(get_client_ip_failed_login_count('192.0.2.55'), 0)
            unlocked_req = rf.post(
                '/api/v1/auth/login/',
                data=json.dumps({'username': 'new_user', 'password': 'ValidPass!'}),
                content_type='application/json',
                REMOTE_ADDR='192.0.2.55',
            )
            self.assertEqual(middleware(unlocked_req).status_code, 200)

            # 4. A different client IP ('192.0.2.88') tracks independently and resets its IP failure counter on 200 OK
            fail_once_req = rf.post(
                '/api/v1/auth/token/',
                data=json.dumps({'username': 'other_user', 'password': 'WrongPass!'}),
                content_type='application/json',
                REMOTE_ADDR='192.0.2.88',
            )
            self.assertEqual(middleware(fail_once_req).status_code, 401)
            self.assertEqual(get_client_ip_failed_login_count('192.0.2.88'), 1)

            ok_req = rf.post(
                '/api/v1/auth/token/',
                data=json.dumps({'username': 'other_user', 'password': 'ValidPass!'}),
                content_type='application/json',
                REMOTE_ADDR='192.0.2.88',
            )
            self.assertEqual(middleware(ok_req).status_code, 200)
            self.assertEqual(get_client_ip_failed_login_count('192.0.2.88'), 0)





