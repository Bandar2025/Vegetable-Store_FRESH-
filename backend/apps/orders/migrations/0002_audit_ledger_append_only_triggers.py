# Generated migration enforcing database-level BEFORE UPDATE/DELETE immutability triggers on orders_orderitem and orders_orderstatushistory

from django.db import migrations


def create_orders_audit_append_only_triggers(apps, schema_editor):
    vendor = schema_editor.connection.vendor
    if vendor == 'postgresql':
        schema_editor.execute(
            """
            CREATE OR REPLACE FUNCTION prevent_orders_audit_mutation()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'Audit table % is append-only; % is prohibited.', TG_TABLE_NAME, TG_OP;
            END;
            $$ LANGUAGE plpgsql;

            DROP TRIGGER IF EXISTS trg_orderitem_no_update ON orders_orderitem;
            CREATE TRIGGER trg_orderitem_no_update
            BEFORE UPDATE ON orders_orderitem
            FOR EACH ROW EXECUTE FUNCTION prevent_orders_audit_mutation();

            DROP TRIGGER IF EXISTS trg_orderitem_no_delete ON orders_orderitem;
            CREATE TRIGGER trg_orderitem_no_delete
            BEFORE DELETE ON orders_orderitem
            FOR EACH ROW EXECUTE FUNCTION prevent_orders_audit_mutation();

            DROP TRIGGER IF EXISTS trg_orderstatushistory_no_update ON orders_orderstatushistory;
            CREATE TRIGGER trg_orderstatushistory_no_update
            BEFORE UPDATE ON orders_orderstatushistory
            FOR EACH ROW EXECUTE FUNCTION prevent_orders_audit_mutation();

            DROP TRIGGER IF EXISTS trg_orderstatushistory_no_delete ON orders_orderstatushistory;
            CREATE TRIGGER trg_orderstatushistory_no_delete
            BEFORE DELETE ON orders_orderstatushistory
            FOR EACH ROW EXECUTE FUNCTION prevent_orders_audit_mutation();
            """,
            params=None,
        )
    elif vendor == 'sqlite':
        schema_editor.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_orderitem_no_update
            BEFORE UPDATE ON orders_orderitem
            BEGIN
                SELECT RAISE(ABORT, 'orders_orderitem snapshots are immutable; UPDATE is prohibited.');
            END;
            """
        )
        schema_editor.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_orderitem_no_delete
            BEFORE DELETE ON orders_orderitem
            BEGIN
                SELECT RAISE(ABORT, 'orders_orderitem snapshots are immutable; DELETE is prohibited.');
            END;
            """
        )
        schema_editor.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_orderstatushistory_no_update
            BEFORE UPDATE ON orders_orderstatushistory
            BEGIN
                SELECT RAISE(ABORT, 'orders_orderstatushistory ledger is append-only; UPDATE is prohibited.');
            END;
            """
        )
        schema_editor.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_orderstatushistory_no_delete
            BEFORE DELETE ON orders_orderstatushistory
            BEGIN
                SELECT RAISE(ABORT, 'orders_orderstatushistory ledger is append-only; DELETE is prohibited.');
            END;
            """
        )


def drop_orders_audit_append_only_triggers(apps, schema_editor):
    vendor = schema_editor.connection.vendor
    if vendor == 'postgresql':
        schema_editor.execute(
            """
            DROP TRIGGER IF EXISTS trg_orderitem_no_update ON orders_orderitem;
            DROP TRIGGER IF EXISTS trg_orderitem_no_delete ON orders_orderitem;
            DROP TRIGGER IF EXISTS trg_orderstatushistory_no_update ON orders_orderstatushistory;
            DROP TRIGGER IF EXISTS trg_orderstatushistory_no_delete ON orders_orderstatushistory;
            DROP FUNCTION IF EXISTS prevent_orders_audit_mutation();
            """
        )
    elif vendor == 'sqlite':
        schema_editor.execute("DROP TRIGGER IF EXISTS trg_orderitem_no_update;")
        schema_editor.execute("DROP TRIGGER IF EXISTS trg_orderitem_no_delete;")
        schema_editor.execute("DROP TRIGGER IF EXISTS trg_orderstatushistory_no_update;")
        schema_editor.execute("DROP TRIGGER IF EXISTS trg_orderstatushistory_no_delete;")


class Migration(migrations.Migration):

    dependencies = [
        ('orders', '0001_initial'),
    ]

    operations = [
        migrations.RunPython(
            code=create_orders_audit_append_only_triggers,
            reverse_code=drop_orders_audit_append_only_triggers,
        ),
    ]
