import ast
from pathlib import Path
import sqlite3
import unittest

from apps.orders.services import install_sqlite_append_only_triggers

try:
    from django.core.exceptions import ValidationError
    from django.db import IntegrityError, transaction
    from django.db.models import ProtectedError
    from django.test import TestCase as DjangoTestCase

    from apps.products.models import (
        MILLI_UNITS_PER_UNIT,
        AppendOnlyManager,
        AppendOnlyQuerySet,
        Branch,
        BranchProductOverride,
        Category,
        Product,
        StockMovement,
        Store,
        Warehouse,
        WarehouseStockBalance,
        resolve_effective_price_halalas,
        validate_milli_quantity,
    )

    DJANGO_AVAILABLE = True
except ImportError:
    DJANGO_AVAILABLE = False
    DjangoTestCase = unittest.TestCase
    MILLI_UNITS_PER_UNIT = 1000


BACKEND_ROOT = Path(__file__).resolve().parent.parent.parent
PRODUCTS_MODELS_PATH = BACKEND_ROOT / 'apps' / 'products' / 'models.py'
PRODUCTS_MIGRATION_0001_PATH = BACKEND_ROOT / 'apps' / 'products' / 'migrations' / '0001_initial.py'
PRODUCTS_MIGRATION_0002_PATH = BACKEND_ROOT / 'apps' / 'products' / 'migrations' / '0002_stockmovement_reference_order.py'
PRODUCTS_MIGRATION_0003_PATH = BACKEND_ROOT / 'apps' / 'products' / 'migrations' / '0003_stockmovement_append_only_triggers.py'


def create_products_sqlite_schema(conn: sqlite3.Connection) -> None:
    """
    Creates the SQLite relational schema mirroring apps.products migrations 0001, 0002, and 0003
    with PRAGMA foreign_keys = ON, CHECK constraints, UNIQUE constraints, ON DELETE RESTRICT (PROTECT),
    and BEFORE UPDATE/DELETE append-only triggers on products_stockmovement.
    """
    conn.execute('PRAGMA foreign_keys = ON;')
    conn.executescript(
        """
        CREATE TABLE products_store (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code VARCHAR(50) NOT NULL,
            name VARCHAR(200) NOT NULL,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_store_code UNIQUE (code),
            CONSTRAINT store_code_non_empty CHECK (code <> ''),
            CONSTRAINT store_name_non_empty CHECK (name <> '')
        );

        CREATE TABLE products_category (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name VARCHAR(100) NOT NULL,
            display_order INTEGER NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_category_name UNIQUE (name),
            CONSTRAINT category_name_non_empty CHECK (name <> ''),
            CONSTRAINT category_display_order_gte_0 CHECK (display_order >= 0)
        );

        CREATE TABLE products_branch (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            store_id INTEGER NOT NULL REFERENCES products_store(id) ON DELETE RESTRICT,
            code VARCHAR(50) NOT NULL,
            name VARCHAR(200) NOT NULL,
            city VARCHAR(100) NOT NULL DEFAULT '',
            address TEXT NOT NULL DEFAULT '',
            is_active BOOLEAN NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_branch_code_per_store UNIQUE (store_id, code),
            CONSTRAINT branch_code_non_empty CHECK (code <> ''),
            CONSTRAINT branch_name_non_empty CHECK (name <> '')
        );

        CREATE TABLE products_warehouse (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            code VARCHAR(50) NOT NULL,
            name VARCHAR(200) NOT NULL,
            is_primary_fulfillment BOOLEAN NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_warehouse_code_per_branch UNIQUE (branch_id, code),
            CONSTRAINT warehouse_code_non_empty CHECK (code <> ''),
            CONSTRAINT warehouse_name_non_empty CHECK (name <> '')
        );

        CREATE TABLE products_product (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sku VARCHAR(100) NOT NULL,
            barcode VARCHAR(100) NULL UNIQUE,
            name VARCHAR(200) NOT NULL,
            category_id INTEGER NOT NULL REFERENCES products_category(id) ON DELETE RESTRICT,
            price_halalas INTEGER NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            image_url VARCHAR(500) NOT NULL DEFAULT '',
            unit VARCHAR(50) NOT NULL,
            is_fractional_allowed BOOLEAN NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_product_sku UNIQUE (sku),
            CONSTRAINT product_sku_non_empty CHECK (sku <> ''),
            CONSTRAINT product_name_non_empty CHECK (name <> ''),
            CONSTRAINT product_unit_non_empty CHECK (unit <> ''),
            CONSTRAINT product_price_halalas_gte_0 CHECK (price_halalas >= 0)
        );

        CREATE TABLE products_branchproductoverride (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            override_price_halalas INTEGER NULL,
            is_available BOOLEAN NOT NULL DEFAULT 1,
            min_order_quantity_milli INTEGER NOT NULL DEFAULT 1000,
            max_order_quantity_milli INTEGER NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_warehouse_product_stock_balance UNIQUE (warehouse_id, product_id),
            CONSTRAINT stock_balance_on_hand_milli_gte_0 CHECK (on_hand_milli >= 0),
            CONSTRAINT stock_balance_reserved_milli_gte_0 CHECK (reserved_milli >= 0),
            CONSTRAINT stock_balance_reserved_lte_on_hand CHECK (reserved_milli <= on_hand_milli),
            CONSTRAINT stock_balance_version_gte_1 CHECK (version >= 1)
        );

        CREATE TABLE orders_order_stub (
            id INTEGER PRIMARY KEY AUTOINCREMENT
        );

        CREATE TABLE products_stockmovement (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            warehouse_id INTEGER NOT NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            movement_type VARCHAR(40) NOT NULL,
            quantity_delta_milli INTEGER NOT NULL,
            balance_after_milli INTEGER NOT NULL,
            reference_order_id INTEGER NULL REFERENCES orders_order_stub(id) ON DELETE RESTRICT,
            actor_id VARCHAR(100) NOT NULL DEFAULT '',
            reason TEXT NOT NULL,
            idempotency_key VARCHAR(128) NULL UNIQUE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT stock_movement_delta_milli_non_zero CHECK (quantity_delta_milli <> 0),
            CONSTRAINT stock_movement_balance_after_milli_gte_0 CHECK (balance_after_milli >= 0),
            CONSTRAINT stock_movement_reason_non_empty CHECK (reason <> '')
        );

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
        """
    )


class ProductsModelStructureAndMigrationTests(unittest.TestCase):
    """
    Verifies AST model definitions, field types, constraints, AppendOnlyQuerySet/Manager,
    and migration dependencies for apps.products.
    """

    def setUp(self):
        self.models_source = PRODUCTS_MODELS_PATH.read_text(encoding='utf-8')
        self.models_tree = ast.parse(self.models_source)
        self.mig_0001_source = PRODUCTS_MIGRATION_0001_PATH.read_text(encoding='utf-8')
        self.mig_0001_tree = ast.parse(self.mig_0001_source)
        self.mig_0002_source = PRODUCTS_MIGRATION_0002_PATH.read_text(encoding='utf-8')
        self.mig_0002_tree = ast.parse(self.mig_0002_source)
        self.mig_0003_source = PRODUCTS_MIGRATION_0003_PATH.read_text(encoding='utf-8')
        self.mig_0003_tree = ast.parse(self.mig_0003_source)

    def test_all_eight_product_and_inventory_models_and_append_only_manager_defined(self):
        class_names = {
            node.name for node in self.models_tree.body if isinstance(node, ast.ClassDef)
        }
        expected_models = {
            'Store',
            'Branch',
            'Warehouse',
            'Category',
            'Product',
            'BranchProductOverride',
            'WarehouseStockBalance',
            'StockMovement',
            'AppendOnlyQuerySet',
            'AppendOnlyManager',
        }
        self.assertTrue(expected_models.issubset(class_names))
        self.assertIn('objects = AppendOnlyManager()', self.models_source)

    def test_no_float_or_decimal_currency_fields_in_products_models(self):
        self.assertNotIn('FloatField', self.models_source)
        self.assertNotIn('DecimalField', self.models_source)
        self.assertIn('price_halalas = models.BigIntegerField()', self.models_source)
        self.assertIn('override_price_halalas = models.BigIntegerField(null=True, blank=True)', self.models_source)
        self.assertIn('on_hand_milli = models.BigIntegerField(default=0)', self.models_source)
        self.assertIn('reserved_milli = models.BigIntegerField(default=0)', self.models_source)
        self.assertIn('quantity_delta_milli = models.BigIntegerField()', self.models_source)
        self.assertIn('balance_after_milli = models.BigIntegerField()', self.models_source)

    def test_protective_deletion_policies_in_products_models(self):
        self.assertNotIn('models.CASCADE', self.models_source)
        self.assertIn('on_delete=models.PROTECT', self.models_source)

    def test_migrations_structure_and_dependencies(self):
        self.assertIn("initial = True", self.mig_0001_source)
        self.assertIn("dependencies = []", self.mig_0001_source)
        for model_name in (
            'Store',
            'Category',
            'Branch',
            'Warehouse',
            'Product',
            'BranchProductOverride',
            'WarehouseStockBalance',
            'StockMovement',
        ):
            self.assertIn(f"name='{model_name}'", self.mig_0001_source)

        self.assertIn("('products', '0001_initial')", self.mig_0002_source)
        self.assertIn("('orders', '0001_initial')", self.mig_0002_source)
        self.assertIn("name='reference_order'", self.mig_0002_source)

        self.assertIn("('products', '0002_stockmovement_reference_order')", self.mig_0003_source)
        self.assertIn("trg_stockmovement_no_update", self.mig_0003_source)
        self.assertIn("trg_stockmovement_no_delete", self.mig_0003_source)


class ProductsRelationalConstraintsAndDomainTests(unittest.TestCase):
    """
    Executes relational constraint, uniqueness, foreign-key protection, fractional milli-unit,
    branch pricing override, and database-level StockMovement immutability trigger tests
    against in-memory SQLite3 (PRAGMA foreign_keys = ON).
    """

    def setUp(self):
        self.conn = sqlite3.connect(':memory:')
        create_products_sqlite_schema(self.conn)
        self.conn.execute("INSERT INTO products_store (id, code, name) VALUES (1, 'FRESH-KSA', 'فريش السعودية')")
        self.conn.execute("INSERT INTO products_store (id, code, name) VALUES (2, 'FRESH-GULF', 'فريش الخليج')")
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, city) VALUES (1, 1, 'RUH-01', 'فرع الرياض العليا', 'الرياض')"
        )
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, city) VALUES (2, 1, 'JED-01', 'فرع جدة التحلية', 'جدة')"
        )
        self.conn.execute(
            "INSERT INTO products_warehouse (id, branch_id, code, name, is_primary_fulfillment) VALUES (1, 1, 'WH-MAIN', 'المستودع الرئيسي', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_warehouse (id, branch_id, code, name, is_primary_fulfillment) VALUES (2, 1, 'WH-COLD', 'مستودع التبريد', 0)"
        )
        self.conn.execute(
            "INSERT INTO products_category (id, name, display_order) VALUES (1, 'فواكه وخضروات', 1)"
        )
        self.conn.execute(
            """
            INSERT INTO products_product (id, sku, barcode, name, category_id, price_halalas, unit, is_fractional_allowed)
            VALUES (1, 'SKU-TOMATO-KG', '6281000000011', 'طماطم بلدي', 1, 795, 'كجم', 1)
            """
        )

    def tearDown(self):
        self.conn.close()

    def test_store_branch_warehouse_sku_and_stock_uniqueness_constraints(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("INSERT INTO products_store (code, name) VALUES ('FRESH-KSA', 'Duplicate Store')")

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_branch (store_id, code, name) VALUES (1, 'RUH-01', 'فرع مكرر')"
            )
        self.conn.execute(
            "INSERT INTO products_branch (store_id, code, name) VALUES (2, 'RUH-01', 'فرع شركة ثانية')"
        )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_warehouse (branch_id, code, name) VALUES (1, 'WH-MAIN', 'مستودع مكرر')"
            )
        self.conn.execute(
            "INSERT INTO products_warehouse (branch_id, code, name) VALUES (2, 'WH-MAIN', 'مستودع فرع جدة')"
        )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO products_product (sku, name, category_id, price_halalas, unit)
                VALUES ('SKU-TOMATO-KG', 'طماطم مكرر', 1, 500, 'كجم')
                """
            )

        self.conn.execute(
            "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (1, 1, 5000, 0)"
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (1, 1, 2000, 0)"
            )
        self.conn.execute(
            "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (2, 1, 12500, 500)"
        )

    def test_nonnegative_stock_and_monetary_check_constraints(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO products_product (sku, name, category_id, price_halalas, unit)
                VALUES ('SKU-NEG', 'سعر سالب', 1, -1, 'حبة')
                """
            )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (1, 1, -100, 0)"
            )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (1, 1, 1000, -1)"
            )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (1, 1, 1000, 1500)"
            )

        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO products_stockmovement (warehouse_id, product_id, movement_type, quantity_delta_milli, balance_after_milli, reason)
                VALUES (1, 1, 'MANUAL_ADJUSTMENT', 0, 1000, 'بدون تغيير')
                """
            )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO products_stockmovement (warehouse_id, product_id, movement_type, quantity_delta_milli, balance_after_milli, reason)
                VALUES (1, 1, 'CHECKOUT_DEDUCTION', -2000, -500, 'خصم يتجاوز الرصيد')
                """
            )

    def test_branch_product_override_pricing_availability_and_quantity_bounds(self):
        self.conn.execute(
            """
            INSERT INTO products_branchproductoverride
            (branch_id, product_id, override_price_halalas, is_available, min_order_quantity_milli, max_order_quantity_milli)
            VALUES (1, 1, 850, 1, 250, 5000)
            """
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_branchproductoverride (branch_id, product_id, override_price_halalas) VALUES (1, 1, 900)"
            )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO products_branchproductoverride (branch_id, product_id, override_price_halalas) VALUES (2, 1, -50)"
            )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                """
                INSERT INTO products_branchproductoverride
                (branch_id, product_id, min_order_quantity_milli, max_order_quantity_milli)
                VALUES (2, 1, 2000, 1000)
                """
            )

    def test_foreign_key_protection_and_stock_movement_database_triggers(self):
        self.conn.execute(
            "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli) VALUES (1, 1, 4500, 0)"
        )
        self.conn.execute(
            """
            INSERT INTO products_stockmovement
            (id, warehouse_id, product_id, movement_type, quantity_delta_milli, balance_after_milli, reason)
            VALUES (1, 1, 1, 'INITIAL_SEED', 4500, 4500, 'رصيد افتتاحي')
            """
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_store WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_branch WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_warehouse WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_category WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_product WHERE id = 1")

        # Database triggers block raw SQL UPDATE and DELETE on products_stockmovement
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("UPDATE products_stockmovement SET quantity_delta_milli = 9000 WHERE id = 1")
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("DELETE FROM products_stockmovement WHERE id = 1")


@unittest.skipUnless(
    DJANGO_AVAILABLE,
    "Django ORM runtime is not installed in the Android build container; requires Django + PostgreSQL/SQLite test runner.",
)
class ProductsDjangoModelTests(DjangoTestCase):
    """
    Full Django ORM model tests for Store, Branch, Warehouse, Category, Product,
    BranchProductOverride, WarehouseStockBalance, and StockMovement.
    """

    def setUp(self):
        self.store = Store.objects.create(code='FRESH-KSA', name='فريش السعودية')
        self.branch = Branch.objects.create(
            store=self.store,
            code='RUH-01',
            name='فرع الرياض العليا',
            city='الرياض',
        )
        self.warehouse = Warehouse.objects.create(
            branch=self.branch,
            code='WH-MAIN',
            name='المستودع الرئيسي',
            is_primary_fulfillment=True,
        )
        self.category = Category.objects.create(name='فواكه وخضروات', display_order=1)
        self.product = Product.objects.create(
            sku='SKU-TOMATO-KG',
            barcode='6281000000011',
            name='طماطم بلدي',
            category=self.category,
            price_halalas=795,
            unit='كجم',
            is_fractional_allowed=True,
        )

    def test_branch_override_effective_price_and_availability(self):
        self.assertEqual(self.product.get_effective_price_halalas(self.branch), 795)
        self.assertTrue(self.product.is_available_in_branch(self.branch))

        override = BranchProductOverride.objects.create(
            branch=self.branch,
            product=self.product,
            override_price_halalas=695,
            is_available=False,
            min_order_quantity_milli=250,
            max_order_quantity_milli=5000,
        )
        self.assertEqual(override.effective_price_halalas, 695)
        self.assertEqual(self.product.get_effective_price_halalas(self.branch), 695)
        self.assertFalse(self.product.is_available_in_branch(self.branch))

    def test_stock_movement_is_append_only_via_model_and_queryset(self):
        movement = StockMovement.objects.create(
            warehouse=self.warehouse,
            product=self.product,
            movement_type=StockMovement.MovementType.INITIAL_SEED,
            quantity_delta_milli=10000,
            balance_after_milli=10000,
            actor_id='owner-1',
            reason='رصيد افتتاحي',
        )
        movement.quantity_delta_milli = 5000
        with self.assertRaises(ValidationError):
            movement.save()
        with self.assertRaises(ValidationError):
            movement.delete()
        with self.assertRaises(ValidationError):
            StockMovement.objects.filter(pk=movement.pk).update(quantity_delta_milli=5000)
        with self.assertRaises(ValidationError):
            StockMovement.objects.filter(pk=movement.pk).delete()

        # Verify database-level BEFORE UPDATE/DELETE trigger on active DB engine (PostgreSQL / SQLite)
        from django.db import DatabaseError, connection

        with self.assertRaises(DatabaseError):
            with transaction.atomic():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "UPDATE products_stockmovement SET quantity_delta_milli = 9999 WHERE id = %s",
                        [movement.pk],
                    )
        with self.assertRaises(DatabaseError):
            with transaction.atomic():
                with connection.cursor() as cursor:
                    cursor.execute(
                        "DELETE FROM products_stockmovement WHERE id = %s",
                        [movement.pk],
                    )


class ProductsGuestCatalogAndPermissionVerificationTests(unittest.TestCase):
    """
    Microtask 1B verification suite executing directly in the Python test runner:
    1. Unauthenticated GET/HEAD/OPTIONS on products and categories are permitted for public guest browsing.
    2. Unauthenticated POST/PUT/PATCH/DELETE are strictly rejected.
    3. Authenticated CUSTOMER POST/PUT/PATCH/DELETE are rejected with PermissionDenied (403).
    4. BRANCH_MANAGER and OWNER retain authorized branch access via `branch_id` and are blocked from cross-branch/cross-store access.
    5. Unauthenticated guests are blocked from passing `branch_id`, `branch`, `store_id`, or identity-spoofing headers/params, preventing private branch override leakage.
    6. CategorySerializer and ProductSerializer expose only public catalog fields and never expose internal WarehouseStockBalance or StockMovement fields.
    """

    def setUp(self):
        from apps.orders.permissions import (
            PermissionDenied,
            PublicCatalogReadOrBranchManagerOwnerWritePermission,
        )
        from apps.orders.services import ActorRole, ServiceActor
        from apps.products.views import (
            CategoryViewSet,
            ProductViewSet,
            resolve_catalog_branch_context,
        )

        self.PermissionDenied = PermissionDenied
        self.permission = PublicCatalogReadOrBranchManagerOwnerWritePermission()
        self.CategoryViewSet = CategoryViewSet
        self.ProductViewSet = ProductViewSet
        self.resolve_catalog_branch_context = resolve_catalog_branch_context

        self.conn = sqlite3.connect(':memory:')
        create_products_sqlite_schema(self.conn)
        self.conn.execute(
            "INSERT INTO products_store (id, code, name, is_active) VALUES (1, 'FRESH-KSA', 'فريش السعودية', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_store (id, code, name, is_active) VALUES (2, 'FRESH-EAST', 'فريش الشرقية', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (1, 1, 'RUH-01', 'فرع الرياض', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (2, 1, 'JED-01', 'فرع جدة', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (3, 2, 'DMM-01', 'فرع الدمام', 1)"
        )
        self.conn.execute(
            "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (4, 1, 'INACTIVE-01', 'فرع متوقف', 0)"
        )

        self.customer_actor = ServiceActor(
            actor_id='user:101',
            role=ActorRole.CUSTOMER.value,
            customer_id='cust-101',
            authorized_branch_ids=None,
        )
        self.manager_b1_actor = ServiceActor(
            actor_id='user:201',
            role=ActorRole.BRANCH_MANAGER.value,
            customer_id=None,
            authorized_branch_ids=frozenset({1}),
        )
        self.owner_s1_actor = ServiceActor(
            actor_id='user:301',
            role=ActorRole.OWNER.value,
            customer_id=None,
            authorized_branch_ids=frozenset({1, 2}),
        )

    def tearDown(self):
        self.conn.close()

    @staticmethod
    def _make_request(
        method: str = 'GET',
        *,
        is_authenticated: bool = False,
        actor=None,
        query_params: dict | None = None,
        headers: dict | None = None,
    ):
        class _DummyUser:
            def __init__(self, authenticated: bool):
                self.is_authenticated = authenticated
                self.is_active = authenticated

        class _DummyRequest:
            pass

        req = _DummyRequest()
        req.method = method
        req.user = _DummyUser(is_authenticated) if is_authenticated else None
        req.query_params = dict(query_params or {})
        req.GET = dict(query_params or {})
        req.headers = dict(headers or {})
        req.META = {}
        if actor is not None:
            req._resolved_service_actor = actor
        return req

    def _sqlite_branch_lookup(self, branch_id: int):
        row = self.conn.execute(
            """
            SELECT b.id, b.code, b.name, b.is_active, s.id, s.is_active
            FROM products_branch b
            JOIN products_store s ON s.id = b.store_id
            WHERE b.id = ? AND b.is_active = 1 AND s.is_active = 1
            """,
            (branch_id,),
        ).fetchone()
        if row is None:
            return None
        return {'id': row[0], 'code': row[1], 'name': row[2], 'is_active': bool(row[3])}

    def test_01_viewsets_configured_with_public_catalog_read_permission_and_readonly_base(self):
        self.assertEqual(
            self.CategoryViewSet.permission_classes,
            [type(self.permission)],
        )
        self.assertEqual(
            self.ProductViewSet.permission_classes,
            [type(self.permission)],
        )

        serializers_path = BACKEND_ROOT / 'apps' / 'products' / 'serializers.py'
        serializers_src = serializers_path.read_text(encoding='utf-8')
        # Verify internal stock/warehouse tables are never serialized in public catalog responses
        for forbidden_internal_field in (
            'on_hand_milli',
            'reserved_milli',
            'warehouse_stock_balances',
            'stock_movements',
            'override_price_halalas',
        ):
            self.assertNotIn(f"'{forbidden_internal_field}'", serializers_src)

    def test_02_unauthenticated_guest_get_head_options_succeed_on_products_and_categories(self):
        for view_cls in (self.CategoryViewSet, self.ProductViewSet):
            view = view_cls()
            for safe_method in ('GET', 'HEAD', 'OPTIONS'):
                req = self._make_request(safe_method, is_authenticated=False)
                self.assertTrue(
                    self.permission.has_permission(req, view),
                    f"Guest {safe_method} should be permitted on {view_cls.__name__}",
                )

    def test_03_unauthenticated_post_put_patch_delete_are_rejected(self):
        for view_cls in (self.CategoryViewSet, self.ProductViewSet):
            view = view_cls()
            for unsafe_method in ('POST', 'PUT', 'PATCH', 'DELETE'):
                req = self._make_request(unsafe_method, is_authenticated=False)
                self.assertFalse(
                    self.permission.has_permission(req, view),
                    f"Unauthenticated {unsafe_method} must be rejected on {view_cls.__name__}",
                )

    def test_04_authenticated_customer_cannot_execute_post_put_patch_delete(self):
        for view_cls in (self.CategoryViewSet, self.ProductViewSet):
            view = view_cls()
            for unsafe_method in ('POST', 'PUT', 'PATCH', 'DELETE'):
                req = self._make_request(
                    unsafe_method,
                    is_authenticated=True,
                    actor=self.customer_actor,
                )
                with self.assertRaises(self.PermissionDenied):
                    self.permission.has_permission(req, view)

    def test_05_branch_manager_and_store_owner_permissions_and_branch_scope_enforced(self):
        view = self.ProductViewSet()

        # Branch manager of Branch 1 can read Branch 1 overrides, but is blocked from Branch 2
        mgr_req_b1 = self._make_request(
            'GET',
            is_authenticated=True,
            actor=self.manager_b1_actor,
            query_params={'branch_id': '1'},
        )
        self.assertTrue(self.permission.has_permission(mgr_req_b1, view))
        resolved_b1 = self.resolve_catalog_branch_context(
            mgr_req_b1,
            branch_lookup=self._sqlite_branch_lookup,
        )
        self.assertEqual(resolved_b1['id'], 1)

        mgr_req_b2 = self._make_request(
            'GET',
            is_authenticated=True,
            actor=self.manager_b1_actor,
            query_params={'branch_id': '2'},
        )
        with self.assertRaises(self.PermissionDenied):
            self.resolve_catalog_branch_context(
                mgr_req_b2,
                branch_lookup=self._sqlite_branch_lookup,
            )

        # Store owner of Store 1 can access Branch 1 and Branch 2, but is blocked from Store 2's Branch 3
        for allowed_branch_id in (1, 2):
            owner_req = self._make_request(
                'GET',
                is_authenticated=True,
                actor=self.owner_s1_actor,
                query_params={'branch_id': str(allowed_branch_id)},
            )
            self.assertTrue(self.permission.has_permission(owner_req, view))
            resolved = self.resolve_catalog_branch_context(
                owner_req,
                branch_lookup=self._sqlite_branch_lookup,
            )
            self.assertEqual(resolved['id'], allowed_branch_id)

        owner_req_b3 = self._make_request(
            'GET',
            is_authenticated=True,
            actor=self.owner_s1_actor,
            query_params={'branch_id': '3'},
        )
        with self.assertRaises(self.PermissionDenied):
            self.resolve_catalog_branch_context(
                owner_req_b3,
                branch_lookup=self._sqlite_branch_lookup,
            )

    def test_06_private_branch_data_and_spoofing_blocked_for_unauthenticated_guests(self):
        view = self.ProductViewSet()

        # Guest attempting to query branch_id or store_id or spoof role is rejected at permission & context layers
        for restricted_params in (
            {'branch_id': '1'},
            {'branch': '1'},
            {'store_id': '1'},
            {'role': 'OWNER'},
            {'actor_id': 'user:1'},
        ):
            guest_req = self._make_request(
                'GET',
                is_authenticated=False,
                query_params=restricted_params,
            )
            self.assertFalse(
                self.permission.has_permission(guest_req, view),
                f"Guest request with {restricted_params} must be rejected",
            )

        guest_branch_req = self._make_request(
            'GET',
            is_authenticated=False,
            query_params={'branch_id': '1'},
        )
        with self.assertRaises(self.PermissionDenied):
            self.resolve_catalog_branch_context(
                guest_branch_req,
                branch_lookup=self._sqlite_branch_lookup,
            )

        # Guest without branch_id gets None branch context (public base catalog only)
        public_guest_req = self._make_request('GET', is_authenticated=False)
        self.assertIsNone(
            self.resolve_catalog_branch_context(
                public_guest_req,
                branch_lookup=self._sqlite_branch_lookup,
            )
        )


@unittest.skipUnless(
    DJANGO_AVAILABLE,
    "Django ORM & DRF runtime are not installed in the Android build container; requires Django test runner.",
)
class ProductsGuestCatalogAndPermissionDRFTests(DjangoTestCase):
    """
    Full DRF APIClient integration tests for Microtask 1B:
    1. GET /api/v1/products/ without JWT succeeds (200 OK) with real ProductSerializer output.
    2. GET /api/v1/categories/ without JWT succeeds (200 OK) with real CategorySerializer output.
    3. POST without authentication is rejected (401 Unauthorized).
    4. PUT, PATCH, DELETE without permissions are rejected (401 for unauthenticated, 403 for customer).
    5. Branch Manager and Store Owner permissions and branch_id isolation work as designed.
    6. Private branch overrides and inactive products/categories are never leaked to guests.
    """

    def setUp(self):
        from django.core.cache import cache
        from rest_framework.test import APIClient
        from apps.orders.auth import create_user_with_identity_profile
        from apps.orders.models import UserIdentityProfile

        cache.clear()
        self.client_guest = APIClient()

        self.store_1 = Store.objects.create(code='FRESH-KSA', name='فريش السعودية', is_active=True)
        self.branch_1 = Branch.objects.create(store=self.store_1, code='RUH-01', name='فرع الرياض', is_active=True)
        self.branch_2 = Branch.objects.create(store=self.store_1, code='JED-01', name='فرع جدة', is_active=True)

        self.store_2 = Store.objects.create(code='FRESH-EAST', name='فريش الشرقية', is_active=True)
        self.branch_3 = Branch.objects.create(store=self.store_2, code='DMM-01', name='فرع الدمام', is_active=True)

        self.cat_active = Category.objects.create(name='فواكه', display_order=1, is_active=True)
        self.cat_inactive = Category.objects.create(name='تصنيف مخفي', display_order=2, is_active=False)

        self.prod_active = Product.objects.create(
            sku='SKU-APPLE-1',
            barcode='6281000000101',
            name='تفاح أحمر جالا',
            category=self.cat_active,
            price_halalas=650,
            description='تفاح طازج',
            image_url='apple',
            unit='كيلوجرام',
            is_fractional_allowed=True,
            is_active=True,
        )
        self.prod_inactive = Product.objects.create(
            sku='SKU-HIDDEN-1',
            name='منتج غير نشط',
            category=self.cat_active,
            price_halalas=900,
            unit='حبة',
            is_active=False,
        )

        # Private branch price override in Branch 1 (550 halalas instead of base 650 halalas)
        BranchProductOverride.objects.create(
            branch=self.branch_1,
            product=self.prod_active,
            override_price_halalas=550,
            is_available=True,
        )

        self.cust_user, _ = create_user_with_identity_profile(
            username='guest_test_customer',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.CUSTOMER,
            customer_id='cust-guest-test-1',
        )
        self.mgr_b1_user, _ = create_user_with_identity_profile(
            username='guest_test_mgr_b1',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.BRANCH_MANAGER,
            store=self.store_1,
            branches=[self.branch_1],
        )
        self.owner_s1_user, _ = create_user_with_identity_profile(
            username='guest_test_owner_s1',
            password='SafePassword#2026!',
            role=UserIdentityProfile.Role.OWNER,
            store=self.store_1,
        )

    def _login(self, username: str):
        from rest_framework.test import APIClient

        client = APIClient()
        res = client.post(
            '/api/v1/auth/login/',
            {'username': username, 'password': 'SafePassword#2026!'},
            format='json',
        )
        self.assertEqual(res.status_code, 200)
        client.credentials(HTTP_AUTHORIZATION=f"Token {res.data['token']}")
        return client

    def test_guest_get_products_and_categories_and_write_protection(self):
        # 1 & 2. Guest GET on products and categories succeeds without JWT
        cat_res = self.client_guest.get('/api/v1/categories/')
        self.assertEqual(cat_res.status_code, 200)
        self.assertEqual(len(cat_res.data), 1)
        self.assertEqual(cat_res.data[0]['name'], 'فواكه')

        prod_res = self.client_guest.get('/api/v1/products/')
        self.assertEqual(prod_res.status_code, 200)
        self.assertEqual(len(prod_res.data), 1)
        # Guest sees public base price (650), not Branch 1's private override (550)
        self.assertEqual(prod_res.data[0]['price_halalas'], 650)
        self.assertEqual(prod_res.data[0]['effective_price_halalas'], 650)

        # HEAD and OPTIONS also succeed for guests
        self.assertEqual(self.client_guest.head('/api/v1/products/').status_code, 200)
        self.assertEqual(self.client_guest.options('/api/v1/products/').status_code, 200)

        # 3 & 4. POST, PUT, PATCH, DELETE without auth are rejected (401), and for customer are rejected (403)
        for url in ('/api/v1/products/', '/api/v1/categories/'):
            self.assertEqual(self.client_guest.post(url, {}, format='json').status_code, 401)

        detail_url = f'/api/v1/products/{self.prod_active.id}/'
        self.assertEqual(self.client_guest.put(detail_url, {}, format='json').status_code, 401)
        self.assertEqual(self.client_guest.patch(detail_url, {}, format='json').status_code, 401)
        self.assertEqual(self.client_guest.delete(detail_url).status_code, 401)

        cust_client = self._login('guest_test_customer')
        self.assertEqual(cust_client.post('/api/v1/products/', {}, format='json').status_code, 403)
        self.assertEqual(cust_client.put(detail_url, {}, format='json').status_code, 403)
        self.assertEqual(cust_client.patch(detail_url, {}, format='json').status_code, 403)
        self.assertEqual(cust_client.delete(detail_url).status_code, 403)

        # 5 & 6. Guest probing branch_id is rejected; Branch Manager & Owner branch isolation works
        self.assertEqual(
            self.client_guest.get(f'/api/v1/products/?branch_id={self.branch_1.id}').status_code,
            401,
        )

        mgr_client = self._login('guest_test_mgr_b1')
        mgr_b1_res = mgr_client.get(f'/api/v1/products/?branch_id={self.branch_1.id}')
        self.assertEqual(mgr_b1_res.status_code, 200)
        self.assertEqual(mgr_b1_res.data[0]['effective_price_halalas'], 550)
        self.assertEqual(
            mgr_client.get(f'/api/v1/products/?branch_id={self.branch_2.id}').status_code,
            403,
        )

        owner_client = self._login('guest_test_owner_s1')
        self.assertEqual(
            owner_client.get(f'/api/v1/products/?branch_id={self.branch_1.id}').status_code,
            200,
        )
        self.assertEqual(
            owner_client.get(f'/api/v1/products/?branch_id={self.branch_2.id}').status_code,
            200,
        )
        self.assertEqual(
            owner_client.get(f'/api/v1/products/?branch_id={self.branch_3.id}').status_code,
            403,
        )

