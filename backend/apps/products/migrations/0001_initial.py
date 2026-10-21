# Generated migration for apps.products multi-branch catalog and warehouse inventory models

from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):

    initial = True

    dependencies = []

    operations = [
        migrations.CreateModel(
            name='Store',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('code', models.CharField(max_length=50, unique=True)),
                ('name', models.CharField(max_length=200)),
                ('is_active', models.BooleanField(default=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
            options={
                'db_table': 'products_store',
                'ordering': ['code'],
            },
        ),
        migrations.CreateModel(
            name='Category',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=100, unique=True)),
                ('display_order', models.IntegerField(default=0)),
                ('is_active', models.BooleanField(default=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
            options={
                'db_table': 'products_category',
                'ordering': ['display_order', 'name'],
            },
        ),
        migrations.CreateModel(
            name='Branch',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('code', models.CharField(max_length=50)),
                ('name', models.CharField(max_length=200)),
                ('city', models.CharField(blank=True, default='', max_length=100)),
                ('address', models.TextField(blank=True, default='')),
                ('is_active', models.BooleanField(default=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                (
                    'store',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='branches',
                        to='products.store',
                    ),
                ),
            ],
            options={
                'db_table': 'products_branch',
                'ordering': ['store', 'code'],
            },
        ),
        migrations.CreateModel(
            name='Warehouse',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('code', models.CharField(max_length=50)),
                ('name', models.CharField(max_length=200)),
                ('is_primary_fulfillment', models.BooleanField(default=False)),
                ('is_active', models.BooleanField(default=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                (
                    'branch',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='warehouses',
                        to='products.branch',
                    ),
                ),
            ],
            options={
                'db_table': 'products_warehouse',
                'ordering': ['branch', 'code'],
            },
        ),
        migrations.CreateModel(
            name='Product',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('sku', models.CharField(max_length=100, unique=True)),
                ('barcode', models.CharField(blank=True, max_length=100, null=True, unique=True)),
                ('name', models.CharField(max_length=200)),
                ('price_halalas', models.BigIntegerField()),
                ('description', models.TextField(blank=True, default='')),
                ('image_url', models.CharField(blank=True, default='', max_length=500)),
                ('unit', models.CharField(max_length=50)),
                ('is_fractional_allowed', models.BooleanField(default=False)),
                ('is_active', models.BooleanField(default=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                (
                    'category',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='products',
                        to='products.category',
                    ),
                ),
            ],
            options={
                'db_table': 'products_product',
                'ordering': ['name'],
            },
        ),
        migrations.CreateModel(
            name='BranchProductOverride',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('override_price_halalas', models.BigIntegerField(blank=True, null=True)),
                ('is_available', models.BooleanField(default=True)),
                ('min_order_quantity_milli', models.BigIntegerField(default=1000)),
                ('max_order_quantity_milli', models.BigIntegerField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                (
                    'branch',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='product_overrides',
                        to='products.branch',
                    ),
                ),
                (
                    'product',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='branch_overrides',
                        to='products.product',
                    ),
                ),
            ],
            options={
                'db_table': 'products_branchproductoverride',
                'ordering': ['branch', 'product'],
            },
        ),
        migrations.CreateModel(
            name='WarehouseStockBalance',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('on_hand_milli', models.BigIntegerField(default=0)),
                ('reserved_milli', models.BigIntegerField(default=0)),
                ('version', models.BigIntegerField(default=1)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
                (
                    'product',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='warehouse_stock_balances',
                        to='products.product',
                    ),
                ),
                (
                    'warehouse',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='stock_balances',
                        to='products.warehouse',
                    ),
                ),
            ],
            options={
                'db_table': 'products_warehousestockbalance',
                'ordering': ['warehouse', 'product'],
            },
        ),
        migrations.CreateModel(
            name='StockMovement',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                (
                    'movement_type',
                    models.CharField(
                        choices=[
                            ('INITIAL_SEED', 'Initial Seed'),
                            ('RESTOCK', 'Restock'),
                            ('CHECKOUT_DEDUCTION', 'Checkout Deduction'),
                            ('CANCELLATION_RESTORATION', 'Cancellation Restoration'),
                            ('MANUAL_ADJUSTMENT', 'Manual Adjustment'),
                            ('WASTAGE', 'Wastage'),
                            ('TRANSFER_IN', 'Transfer In'),
                            ('TRANSFER_OUT', 'Transfer Out'),
                        ],
                        max_length=40,
                    ),
                ),
                ('quantity_delta_milli', models.BigIntegerField()),
                ('balance_after_milli', models.BigIntegerField()),
                ('actor_id', models.CharField(blank=True, default='', max_length=100)),
                ('reason', models.TextField()),
                ('idempotency_key', models.CharField(blank=True, max_length=128, null=True, unique=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                (
                    'product',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='stock_movements',
                        to='products.product',
                    ),
                ),
                (
                    'warehouse',
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name='stock_movements',
                        to='products.warehouse',
                    ),
                ),
            ],
            options={
                'db_table': 'products_stockmovement',
                'ordering': ['-created_at', '-id'],
            },
        ),
        # Store constraints
        migrations.AddConstraint(
            model_name='store',
            constraint=models.UniqueConstraint(fields=('code',), name='uniq_store_code'),
        ),
        migrations.AddConstraint(
            model_name='store',
            constraint=models.CheckConstraint(check=models.Q(('code', ''), _negated=True), name='store_code_non_empty'),
        ),
        migrations.AddConstraint(
            model_name='store',
            constraint=models.CheckConstraint(check=models.Q(('name', ''), _negated=True), name='store_name_non_empty'),
        ),
        # Category constraints
        migrations.AddConstraint(
            model_name='category',
            constraint=models.UniqueConstraint(fields=('name',), name='uniq_category_name'),
        ),
        migrations.AddConstraint(
            model_name='category',
            constraint=models.CheckConstraint(check=models.Q(('name', ''), _negated=True), name='category_name_non_empty'),
        ),
        migrations.AddConstraint(
            model_name='category',
            constraint=models.CheckConstraint(
                check=models.Q(('display_order__gte', 0)),
                name='category_display_order_gte_0',
            ),
        ),
        # Branch indexes & constraints
        migrations.AddIndex(
            model_name='branch',
            index=models.Index(fields=['store', 'is_active'], name='idx_branch_store_active'),
        ),
        migrations.AddConstraint(
            model_name='branch',
            constraint=models.UniqueConstraint(fields=('store', 'code'), name='uniq_branch_code_per_store'),
        ),
        migrations.AddConstraint(
            model_name='branch',
            constraint=models.CheckConstraint(check=models.Q(('code', ''), _negated=True), name='branch_code_non_empty'),
        ),
        migrations.AddConstraint(
            model_name='branch',
            constraint=models.CheckConstraint(check=models.Q(('name', ''), _negated=True), name='branch_name_non_empty'),
        ),
        # Warehouse indexes & constraints
        migrations.AddIndex(
            model_name='warehouse',
            index=models.Index(fields=['branch', 'is_active'], name='idx_warehouse_branch_active'),
        ),
        migrations.AddConstraint(
            model_name='warehouse',
            constraint=models.UniqueConstraint(fields=('branch', 'code'), name='uniq_warehouse_code_per_branch'),
        ),
        migrations.AddConstraint(
            model_name='warehouse',
            constraint=models.CheckConstraint(
                check=models.Q(('code', ''), _negated=True),
                name='warehouse_code_non_empty',
            ),
        ),
        migrations.AddConstraint(
            model_name='warehouse',
            constraint=models.CheckConstraint(
                check=models.Q(('name', ''), _negated=True),
                name='warehouse_name_non_empty',
            ),
        ),
        # Product indexes & constraints
        migrations.AddIndex(
            model_name='product',
            index=models.Index(fields=['category', 'is_active'], name='idx_product_category_active'),
        ),
        migrations.AddConstraint(
            model_name='product',
            constraint=models.UniqueConstraint(fields=('sku',), name='uniq_product_sku'),
        ),
        migrations.AddConstraint(
            model_name='product',
            constraint=models.CheckConstraint(check=models.Q(('sku', ''), _negated=True), name='product_sku_non_empty'),
        ),
        migrations.AddConstraint(
            model_name='product',
            constraint=models.CheckConstraint(check=models.Q(('name', ''), _negated=True), name='product_name_non_empty'),
        ),
        migrations.AddConstraint(
            model_name='product',
            constraint=models.CheckConstraint(check=models.Q(('unit', ''), _negated=True), name='product_unit_non_empty'),
        ),
        migrations.AddConstraint(
            model_name='product',
            constraint=models.CheckConstraint(
                check=models.Q(('price_halalas__gte', 0)),
                name='product_price_halalas_gte_0',
            ),
        ),
        # BranchProductOverride indexes & constraints
        migrations.AddIndex(
            model_name='branchproductoverride',
            index=models.Index(fields=['branch', 'is_available'], name='idx_branch_override_avail'),
        ),
        migrations.AddConstraint(
            model_name='branchproductoverride',
            constraint=models.UniqueConstraint(
                fields=('branch', 'product'),
                name='uniq_branch_product_override',
            ),
        ),
        migrations.AddConstraint(
            model_name='branchproductoverride',
            constraint=models.CheckConstraint(
                check=models.Q(('override_price_halalas__isnull', True), ('override_price_halalas__gte', 0), _connector='OR'),
                name='branch_override_price_halalas_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='branchproductoverride',
            constraint=models.CheckConstraint(
                check=models.Q(('min_order_quantity_milli__gt', 0)),
                name='branch_override_min_qty_milli_gt_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='branchproductoverride',
            constraint=models.CheckConstraint(
                check=models.Q(
                    ('max_order_quantity_milli__isnull', True),
                    ('max_order_quantity_milli__gte', models.F('min_order_quantity_milli')),
                    _connector='OR',
                ),
                name='branch_override_max_gte_min_qty_milli',
            ),
        ),
        # WarehouseStockBalance indexes & constraints
        migrations.AddIndex(
            model_name='warehousestockbalance',
            index=models.Index(fields=['product', 'warehouse'], name='idx_stock_balance_prod_wh'),
        ),
        migrations.AddConstraint(
            model_name='warehousestockbalance',
            constraint=models.UniqueConstraint(
                fields=('warehouse', 'product'),
                name='uniq_warehouse_product_stock_balance',
            ),
        ),
        migrations.AddConstraint(
            model_name='warehousestockbalance',
            constraint=models.CheckConstraint(
                check=models.Q(('on_hand_milli__gte', 0)),
                name='stock_balance_on_hand_milli_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='warehousestockbalance',
            constraint=models.CheckConstraint(
                check=models.Q(('reserved_milli__gte', 0)),
                name='stock_balance_reserved_milli_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='warehousestockbalance',
            constraint=models.CheckConstraint(
                check=models.Q(('reserved_milli__lte', models.F('on_hand_milli'))),
                name='stock_balance_reserved_lte_on_hand',
            ),
        ),
        migrations.AddConstraint(
            model_name='warehousestockbalance',
            constraint=models.CheckConstraint(
                check=models.Q(('version__gte', 1)),
                name='stock_balance_version_gte_1',
            ),
        ),
        # StockMovement indexes & constraints
        migrations.AddIndex(
            model_name='stockmovement',
            index=models.Index(fields=['warehouse', 'product', '-created_at'], name='idx_stock_move_wh_prod_time'),
        ),
        migrations.AddConstraint(
            model_name='stockmovement',
            constraint=models.CheckConstraint(
                check=models.Q(('quantity_delta_milli', 0), _negated=True),
                name='stock_movement_delta_milli_non_zero',
            ),
        ),
        migrations.AddConstraint(
            model_name='stockmovement',
            constraint=models.CheckConstraint(
                check=models.Q(('balance_after_milli__gte', 0)),
                name='stock_movement_balance_after_milli_gte_0',
            ),
        ),
        migrations.AddConstraint(
            model_name='stockmovement',
            constraint=models.CheckConstraint(
                check=models.Q(('reason', ''), _negated=True),
                name='stock_movement_reason_non_empty',
            ),
        ),
    ]
