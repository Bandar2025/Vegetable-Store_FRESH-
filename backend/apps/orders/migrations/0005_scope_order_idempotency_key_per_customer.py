# Generated migration for Microtask 1D.2 / hardened in Microtask 1D.2.1:
# Scopes Order.idempotency_key uniqueness per (customer_id, idempotency_key)
# instead of a global unique constraint on idempotency_key, preventing cross-customer
# key collisions while preserving strict database-level deduplication per customer.

from __future__ import annotations

import sqlite3

try:
    from django.db import migrations, models
except ImportError:  # pragma: no cover - lightweight shim when Django is not installed
    class _ShimOp:
        def __init__(self, *args, **kwargs):
            self.args = args
            self.kwargs = kwargs

    class migrations:  # type: ignore[no-redef]
        class Migration:
            dependencies: list = []
            operations: list = []

        AlterField = _ShimOp
        AddConstraint = _ShimOp

        class RunPython(_ShimOp):
            @staticmethod
            def noop(apps, schema_editor) -> None:
                return None

    class models:  # type: ignore[no-redef]
        CharField = _ShimOp
        UniqueConstraint = _ShimOp
        CheckConstraint = _ShimOp
        Q = _ShimOp


def normalize_blank_order_idempotency_keys(apps, schema_editor) -> None:
    """
    Safely normalizes any legacy empty-string (`''`) or whitespace-only
    `idempotency_key` values on `Order` to `NULL` before adding the
    `order_idempotency_key_non_empty` check constraint.
    """
    Order = apps.get_model('orders', 'Order')
    db_alias = schema_editor.connection.alias
    # Normalize exact empty string in bulk first
    Order.objects.using(db_alias).filter(idempotency_key='').update(idempotency_key=None)
    # Normalize any whitespace-only keys
    for order in (
        Order.objects.using(db_alias)
        .exclude(idempotency_key__isnull=True)
        .only('id', 'idempotency_key')
    ):
        if order.idempotency_key is not None and not str(order.idempotency_key).strip():
            Order.objects.using(db_alias).filter(pk=order.pk).update(idempotency_key=None)


def apply_migration_0005_sqlite(conn: sqlite3.Connection) -> None:
    """
    Executes the exact forward schema & data transformation of Migration 0005
    (`0004 -> 0005`) on a disposable SQLite database whose `orders_order` table
    was created under Migration 0004 (global `UNIQUE(idempotency_key)` without
    `order_idempotency_key_non_empty` or `uniq_order_customer_idempotency_key`):
      1. Normalizes any blank/whitespace `idempotency_key` values to `NULL`.
      2. Rebuilds `orders_order` to drop the global `UNIQUE(idempotency_key)` constraint,
         add `CHECK (idempotency_key IS NULL OR TRIM(idempotency_key) <> '')`, and
         add `UNIQUE (customer_id, idempotency_key)` while preserving all existing rows.
    """
    conn.execute('PRAGMA foreign_keys = OFF;')
    try:
        conn.execute("DROP TABLE IF EXISTS orders_order__new_0005;")
        with conn:
            conn.execute(
                "UPDATE orders_order SET idempotency_key = NULL WHERE idempotency_key IS NOT NULL AND TRIM(idempotency_key) = ''"
            )
            conn.execute(
                """
                CREATE TABLE orders_order__new_0005 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
                    fulfillment_warehouse_id INTEGER NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
                    customer_id VARCHAR(128) NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    total_halalas BIGINT NOT NULL CHECK (total_halalas >= 0),
                    idempotency_key VARCHAR(128) NULL CHECK (idempotency_key IS NULL OR TRIM(idempotency_key) <> ''),
                    cancellation_restored BOOLEAN NOT NULL DEFAULT 0,
                    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uniq_order_customer_idempotency_key UNIQUE (customer_id, idempotency_key)
                );
                """
            )
            conn.execute(
                """
                INSERT INTO orders_order__new_0005 (
                    id, branch_id, fulfillment_warehouse_id, customer_id, status,
                    total_halalas, idempotency_key, cancellation_restored, timestamp, updated_at
                )
                SELECT
                    id, branch_id, fulfillment_warehouse_id, customer_id, status,
                    total_halalas, idempotency_key, cancellation_restored, timestamp, updated_at
                FROM orders_order
                ORDER BY id ASC;
                """
            )
            conn.execute("DROP TABLE orders_order;")
            conn.execute("ALTER TABLE orders_order__new_0005 RENAME TO orders_order;")
    except Exception:
        conn.execute("DROP TABLE IF EXISTS orders_order__new_0005;")
        raise
    finally:
        conn.execute('PRAGMA foreign_keys = ON;')


def reverse_migration_0005_sqlite(conn: sqlite3.Connection) -> None:
    """
    Executes the reverse schema transformation (`0005 -> 0004`) on a disposable
    SQLite database, restoring the Migration 0004 schema (global `UNIQUE(idempotency_key)`).
    Raises `sqlite3.IntegrityError` if cross-customer duplicate non-null idempotency keys
    exist at the time of reversal.
    """
    conn.execute('PRAGMA foreign_keys = OFF;')
    try:
        conn.execute("DROP TABLE IF EXISTS orders_order__rev_0004;")
        with conn:
            conn.execute(
                """
                CREATE TABLE orders_order__rev_0004 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
                    fulfillment_warehouse_id INTEGER NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
                    customer_id VARCHAR(128) NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    total_halalas BIGINT NOT NULL CHECK (total_halalas >= 0),
                    idempotency_key VARCHAR(128) NULL UNIQUE,
                    cancellation_restored BOOLEAN NOT NULL DEFAULT 0,
                    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                """
            )
            conn.execute(
                """
                INSERT INTO orders_order__rev_0004 (
                    id, branch_id, fulfillment_warehouse_id, customer_id, status,
                    total_halalas, idempotency_key, cancellation_restored, timestamp, updated_at
                )
                SELECT
                    id, branch_id, fulfillment_warehouse_id, customer_id, status,
                    total_halalas, idempotency_key, cancellation_restored, timestamp, updated_at
                FROM orders_order
                ORDER BY id ASC;
                """
            )
            conn.execute("DROP TABLE orders_order;")
            conn.execute("ALTER TABLE orders_order__rev_0004 RENAME TO orders_order;")
    except Exception:
        conn.execute("DROP TABLE IF EXISTS orders_order__rev_0004;")
        raise
    finally:
        conn.execute('PRAGMA foreign_keys = ON;')


class Migration(migrations.Migration):

    dependencies = [
        ('orders', '0004_auth_token_lifecycle_and_rate_limiting'),
    ]

    operations = [
        migrations.AlterField(
            model_name='order',
            name='idempotency_key',
            field=models.CharField(blank=True, max_length=128, null=True),
        ),
        migrations.RunPython(
            code=normalize_blank_order_idempotency_keys,
            reverse_code=migrations.RunPython.noop,
        ),
        migrations.AddConstraint(
            model_name='order',
            constraint=models.UniqueConstraint(
                fields=('customer_id', 'idempotency_key'),
                name='uniq_order_customer_idempotency_key',
            ),
        ),
        migrations.AddConstraint(
            model_name='order',
            constraint=models.CheckConstraint(
                check=models.Q(
                    ('idempotency_key__isnull', True),
                    models.Q(('idempotency_key', ''), _negated=True),
                    _connector='OR',
                ),
                name='order_idempotency_key_non_empty',
            ),
        ),
    ]

