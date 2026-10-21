# Generated migration enforcing database-level BEFORE UPDATE/DELETE immutability triggers on products_stockmovement

from django.db import migrations


def create_stockmovement_append_only_triggers(apps, schema_editor):
    vendor = schema_editor.connection.vendor
    if vendor == 'postgresql':
        schema_editor.execute(
            """
            CREATE OR REPLACE FUNCTION prevent_stockmovement_mutation()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'products_stockmovement ledger is append-only; % is prohibited.', TG_OP;
            END;
            $$ LANGUAGE plpgsql;

            DROP TRIGGER IF EXISTS trg_stockmovement_no_update ON products_stockmovement;
            CREATE TRIGGER trg_stockmovement_no_update
            BEFORE UPDATE ON products_stockmovement
            FOR EACH ROW EXECUTE FUNCTION prevent_stockmovement_mutation();

            DROP TRIGGER IF EXISTS trg_stockmovement_no_delete ON products_stockmovement;
            CREATE TRIGGER trg_stockmovement_no_delete
            BEFORE DELETE ON products_stockmovement
            FOR EACH ROW EXECUTE FUNCTION prevent_stockmovement_mutation();
            """,
            params=None,
        )
    elif vendor == 'sqlite':
        schema_editor.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_stockmovement_no_update
            BEFORE UPDATE ON products_stockmovement
            BEGIN
                SELECT RAISE(ABORT, 'products_stockmovement ledger is append-only; UPDATE is prohibited.');
            END;
            """
        )
        schema_editor.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_stockmovement_no_delete
            BEFORE DELETE ON products_stockmovement
            BEGIN
                SELECT RAISE(ABORT, 'products_stockmovement ledger is append-only; DELETE is prohibited.');
            END;
            """
        )


def drop_stockmovement_append_only_triggers(apps, schema_editor):
    vendor = schema_editor.connection.vendor
    if vendor == 'postgresql':
        schema_editor.execute(
            """
            DROP TRIGGER IF EXISTS trg_stockmovement_no_update ON products_stockmovement;
            DROP TRIGGER IF EXISTS trg_stockmovement_no_delete ON products_stockmovement;
            DROP FUNCTION IF EXISTS prevent_stockmovement_mutation();
            """
        )
    elif vendor == 'sqlite':
        schema_editor.execute("DROP TRIGGER IF EXISTS trg_stockmovement_no_update;")
        schema_editor.execute("DROP TRIGGER IF EXISTS trg_stockmovement_no_delete;")


class Migration(migrations.Migration):

    dependencies = [
        ('products', '0002_stockmovement_reference_order'),
    ]

    operations = [
        migrations.RunPython(
            code=create_stockmovement_append_only_triggers,
            reverse_code=drop_stockmovement_append_only_triggers,
        ),
    ]
