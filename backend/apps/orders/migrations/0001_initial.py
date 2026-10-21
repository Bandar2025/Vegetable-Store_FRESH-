# Generated migration for apps.orders multi-branch order, immutable snapshot, status history, and idempotency models

from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        ('products', '0001_initial'),
    ]

    operations = [
        migrations.CreateModel(
            name='Order',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('customer_id', models.CharField(db_index=True, max_length=100)),
                (
                    'status',
                    models.CharField(
                        choices=[
                            ('PENDING', 'Pending'),
                            ('CONFIRMED', 'Confirmed'),
                            ('PROCESSING', 'Processing'),
                            ('COMPLETED', 'Completed'),
                            ('CANCELLED', 'Cancelled'),
                        ],
                        default='PENDING',
                        max_length=50,
                    ),
                ),
                ('total_halalas', models.BigIntegerField(default=0)),
                ('idempotency_key', models.CharField(blank=True, max_length=128, null=True, unique=True)),
                ('cancellation_restored', models.BooleanField(default=False)),
                ('timestamp', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                (
                    'branch',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='orders',
                        to='products.branch',
                    ),
                ),
                (
                    'fulfillment_warehouse',
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='fulfilled_orders',
                        to='products.warehouse',
                    ),
                ),
            ],
            options={
                'db_table': 'orders_order',
                'ordering': ['-timestamp', '-id'],
            },
        ),
        migrations.CreateModel(
            name='OrderItem',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('product_name_snapshot', models.CharField(max_length=200)),
                ('unit_snapshot', models.CharField(max_length=50)),
                ('quantity_milli', models.BigIntegerField()),
                ('unit_price_halalas', models.BigIntegerField()),
                ('line_total_halalas', models.BigIntegerField()),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                (
                    'order',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='items',
                        to='orders.order',
                    ),
                ),
                (
                    'product',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='order_items',
                        to='products.product',
                    ),
                ),
            ],
            options={
                'db_table': 'orders_orderitem',
                'ordering': ['id'],
            },
        ),
        migrations.CreateModel(
            name='OrderStatusHistory',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                (
                    'from_status',
                    models.CharField(
                        blank=True,
                        choices=[
                            ('PENDING', 'Pending'),
                            ('CONFIRMED', 'Confirmed'),
                            ('PROCESSING', 'Processing'),
                            ('COMPLETED', 'Completed'),
                            ('CANCELLED', 'Cancelled'),
                        ],
                        max_length=50,
                        null=True,
                    ),
                ),
                (
                    'to_status',
                    models.CharField(
                        choices=[
                            ('PENDING', 'Pending'),
                            ('CONFIRMED', 'Confirmed'),
                            ('PROCESSING', 'Processing'),
                            ('COMPLETED', 'Completed'),
                            ('CANCELLED', 'Cancelled'),
                        ],
                        max_length=50,
                    ),
                ),
                ('changed_by_actor_id', models.CharField(blank=True, default='', max_length=100)),
                ('reason', models.TextField(blank=True, default='')),
                ('changed_at', models.DateTimeField(auto_now_add=True)),
                (
                    'order',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='status_history',
                        to='orders.order',
                    ),
                ),
            ],
            options={
                'db_table': 'orders_orderstatushistory',
                'ordering': ['changed_at', 'id'],
            },
        ),
        migrations.CreateModel(
            name='IdempotencyKey',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('actor_id', models.CharField(max_length=100)),
                ('operation', models.CharField(max_length=64)),
                ('idempotency_key', models.CharField(max_length=128)),
                ('request_hash', models.CharField(max_length=64)),
                ('response_status_code', models.IntegerField(blank=True, null=True)),
                ('response_payload', models.JSONField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('expires_at', models.DateTimeField(blank=True, null=True)),
                (
                    'reference_order',
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.SET_NULL,
                        related_name='idempotency_records',
                        to='orders.order',
                    ),
                ),
            ],
            options={
                'db_table': 'orders_idempotencykey',
                'ordering': ['-created_at'],
            },
        ),
        # Order indexes & constraints
        migrations.AddIndex(
            model_name='order',
            index=models.Index(fields=['branch', 'status', '-timestamp'], name='idx_order_branch_status_time'),
        ),
        migrations.AddIndex(
            model_name='order',
            index=models.Index(fields=['customer_id', '-timestamp'], name='idx_order_customer_time'),
        ),
        migrations.AddConstraint(
            model_name='order',
            constraint=models.CheckConstraint(
                check=models.Q(('total_halalas__gte', 0)),
                name='order_total_halalas_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='order',
            constraint=models.CheckConstraint(
                check=models.Q(('customer_id', ''), _negated=True),
                name='order_customer_id_non_empty',
            ),
        ),
        migrations.AddConstraint(
            model_name='order',
            constraint=models.CheckConstraint(
                check=models.Q(
                    (
                        'status__in',
                        ['PENDING', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED'],
                    )
                ),
                name='order_status_valid_choice',
            ),
        ),
        # OrderItem indexes & constraints
        migrations.AddIndex(
            model_name='orderitem',
            index=models.Index(fields=['order'], name='idx_order_item_order'),
        ),
        migrations.AddIndex(
            model_name='orderitem',
            index=models.Index(fields=['product'], name='idx_order_item_product'),
        ),
        migrations.AddConstraint(
            model_name='orderitem',
            constraint=models.CheckConstraint(
                check=models.Q(('quantity_milli__gt', 0)),
                name='order_item_quantity_milli_gt_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='orderitem',
            constraint=models.CheckConstraint(
                check=models.Q(('unit_price_halalas__gte', 0)),
                name='order_item_unit_price_halalas_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='orderitem',
            constraint=models.CheckConstraint(
                check=models.Q(('line_total_halalas__gte', 0)),
                name='order_item_line_total_halalas_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='orderitem',
            constraint=models.CheckConstraint(
                check=models.Q(('product_name_snapshot', ''), _negated=True),
                name='order_item_prod_name_snap_non_empty',
            ),
        ),
        migrations.AddConstraint(
            model_name='orderitem',
            constraint=models.CheckConstraint(
                check=models.Q(('unit_snapshot', ''), _negated=True),
                name='order_item_unit_snap_non_empty',
            ),
        ),
        # OrderStatusHistory indexes & constraints
        migrations.AddIndex(
            model_name='orderstatushistory',
            index=models.Index(fields=['order', '-changed_at'], name='idx_order_status_hist_time'),
        ),
        migrations.AddConstraint(
            model_name='orderstatushistory',
            constraint=models.CheckConstraint(
                check=models.Q(
                    (
                        'to_status__in',
                        ['PENDING', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED'],
                    )
                ),
                name='order_status_hist_to_valid',
            ),
        ),
        migrations.AddConstraint(
            model_name='orderstatushistory',
            constraint=models.CheckConstraint(
                check=models.Q(
                    ('from_status__isnull', True),
                    models.Q(('from_status', models.F('to_status')), _negated=True),
                    _connector='OR',
                ),
                name='order_status_hist_changed',
            ),
        ),
        # IdempotencyKey indexes & constraints
        migrations.AddIndex(
            model_name='idempotencykey',
            index=models.Index(fields=['expires_at'], name='idx_idempotency_expires_at'),
        ),
        migrations.AddConstraint(
            model_name='idempotencykey',
            constraint=models.UniqueConstraint(
                fields=('actor_id', 'operation', 'idempotency_key'),
                name='uniq_actor_operation_idempotency_key',
            ),
        ),
        migrations.AddConstraint(
            model_name='idempotencykey',
            constraint=models.CheckConstraint(
                check=models.Q(('actor_id', ''), _negated=True),
                name='idempotency_actor_id_non_empty',
            ),
        ),
        migrations.AddConstraint(
            model_name='idempotencykey',
            constraint=models.CheckConstraint(
                check=models.Q(('operation', ''), _negated=True),
                name='idempotency_operation_non_empty',
            ),
        ),
        migrations.AddConstraint(
            model_name='idempotencykey',
            constraint=models.CheckConstraint(
                check=models.Q(('idempotency_key', ''), _negated=True),
                name='idempotency_key_non_empty',
            ),
        ),
        migrations.AddConstraint(
            model_name='idempotencykey',
            constraint=models.CheckConstraint(
                check=models.Q(('request_hash', ''), _negated=True),
                name='idempotency_req_hash_non_empty',
            ),
        ),
    ]
