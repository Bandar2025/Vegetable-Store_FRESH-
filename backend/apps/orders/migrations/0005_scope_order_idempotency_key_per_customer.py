# Generated migration for Microtask 1D.2:
# Scopes Order.idempotency_key uniqueness per (customer_id, idempotency_key)
# instead of a global unique constraint on idempotency_key, preventing cross-customer
# key collisions while preserving strict database-level deduplication per customer.

from django.db import migrations, models


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
