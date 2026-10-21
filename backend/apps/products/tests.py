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
