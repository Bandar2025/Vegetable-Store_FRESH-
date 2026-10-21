from django.core.exceptions import ValidationError
from django.db import models
from django.db.models import CheckConstraint, F, Index, Q, UniqueConstraint

MILLI_UNITS_PER_UNIT = 1000


def resolve_effective_price_halalas(base_price_halalas: int, override_price_halalas: int | None = None) -> int:
    """
    Returns the authoritative unit price in integer halalas for a product at a branch.
    If override_price_halalas is explicitly set (not None), it takes precedence over base_price_halalas.
    """
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
    """
    Validates a product quantity expressed in integer milli-units (1000 = 1.000 unit).
    """
    if not isinstance(quantity_milli, int) or isinstance(quantity_milli, bool):
        raise ValueError("quantity_milli must be an integer representing milli-units.")
    if quantity_milli <= 0:
        raise ValueError("quantity_milli must be strictly positive (> 0).")
    if not is_fractional_allowed and (quantity_milli % MILLI_UNITS_PER_UNIT != 0):
        raise ValueError("Fractional quantities are not permitted for this product; quantity_milli must be a multiple of 1000.")
    if min_order_quantity_milli is not None and quantity_milli < min_order_quantity_milli:
        raise ValueError(f"quantity_milli ({quantity_milli}) is below minimum order quantity ({min_order_quantity_milli}).")
    if max_order_quantity_milli is not None and quantity_milli > max_order_quantity_milli:
        raise ValueError(f"quantity_milli ({quantity_milli}) exceeds maximum order quantity ({max_order_quantity_milli}).")
    return quantity_milli


class Store(models.Model):
    """
    Top-level store or retail company owning one or more branches.
    """
    code = models.CharField(max_length=50, unique=True)
    name = models.CharField(max_length=200)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_store'
        ordering = ['code']
        constraints = [
            UniqueConstraint(fields=['code'], name='uniq_store_code'),
            CheckConstraint(check=~Q(code=''), name='store_code_non_empty'),
            CheckConstraint(check=~Q(name=''), name='store_name_non_empty'),
        ]

    def clean(self):
        super().clean()
        if not self.code or not self.code.strip():
            raise ValidationError({'code': 'Store code cannot be empty.'})
        if not self.name or not self.name.strip():
            raise ValidationError({'name': 'Store name cannot be empty.'})

    def __str__(self):
        return f"{self.name} ({self.code})"


class Branch(models.Model):
    """
    Physical or regional store branch belonging to a Store.
    """
    store = models.ForeignKey(
        Store,
        on_delete=models.PROTECT,
        related_name='branches',
    )
    code = models.CharField(max_length=50)
    name = models.CharField(max_length=200)
    city = models.CharField(max_length=100, blank=True, default='')
    address = models.TextField(blank=True, default='')
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_branch'
        ordering = ['store', 'code']
        constraints = [
            UniqueConstraint(fields=['store', 'code'], name='uniq_branch_code_per_store'),
            CheckConstraint(check=~Q(code=''), name='branch_code_non_empty'),
            CheckConstraint(check=~Q(name=''), name='branch_name_non_empty'),
        ]
        indexes = [
            Index(fields=['store', 'is_active'], name='idx_branch_store_active'),
        ]

    def clean(self):
        super().clean()
        if not self.code or not self.code.strip():
            raise ValidationError({'code': 'Branch code cannot be empty.'})
        if not self.name or not self.name.strip():
            raise ValidationError({'name': 'Branch name cannot be empty.'})

    def __str__(self):
        return f"{self.name} [{self.code}]"


class Warehouse(models.Model):
    """
    Inventory storage/fulfillment location linked to a Branch.
    A single branch may operate multiple warehouses (e.g., main floor, cold storage, dark store).
    """
    branch = models.ForeignKey(
        Branch,
        on_delete=models.PROTECT,
        related_name='warehouses',
    )
    code = models.CharField(max_length=50)
    name = models.CharField(max_length=200)
    is_primary_fulfillment = models.BooleanField(default=False)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_warehouse'
        ordering = ['branch', 'code']
        constraints = [
            UniqueConstraint(fields=['branch', 'code'], name='uniq_warehouse_code_per_branch'),
            CheckConstraint(check=~Q(code=''), name='warehouse_code_non_empty'),
            CheckConstraint(check=~Q(name=''), name='warehouse_name_non_empty'),
        ]
        indexes = [
            Index(fields=['branch', 'is_active'], name='idx_warehouse_branch_active'),
        ]

    def clean(self):
        super().clean()
        if not self.code or not self.code.strip():
            raise ValidationError({'code': 'Warehouse code cannot be empty.'})
        if not self.name or not self.name.strip():
            raise ValidationError({'name': 'Warehouse name cannot be empty.'})

    def __str__(self):
        return f"{self.name} ({self.code})"


class Category(models.Model):
    """
    Shared product category across the store catalog.
    """
    name = models.CharField(max_length=100, unique=True)
    display_order = models.IntegerField(default=0)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_category'
        ordering = ['display_order', 'name']
        constraints = [
            UniqueConstraint(fields=['name'], name='uniq_category_name'),
            CheckConstraint(check=~Q(name=''), name='category_name_non_empty'),
            CheckConstraint(check=Q(display_order__gte=0), name='category_display_order_gte_0'),
        ]

    def clean(self):
        super().clean()
        if not self.name or not self.name.strip():
            raise ValidationError({'name': 'Category name cannot be empty.'})
        if self.display_order is not None and self.display_order < 0:
            raise ValidationError({'display_order': 'Display order must be non-negative.'})

    def __str__(self):
        return self.name


class Product(models.Model):
    """
    Shared catalog product definition.
    - Monetary prices are stored as integer SAR halalas (1 SAR = 100 halalas).
    - Global stock is NOT stored on Product; authoritative stock resides in WarehouseStockBalance.
    """
    sku = models.CharField(max_length=100, unique=True)
    barcode = models.CharField(max_length=100, null=True, blank=True, unique=True)
    name = models.CharField(max_length=200)
    category = models.ForeignKey(
        Category,
        on_delete=models.PROTECT,
        related_name='products',
    )
    # Using BigIntegerField for halalas (100 halalas = 1 SAR) per 1000 milli-units (1.000 unit)
    price_halalas = models.BigIntegerField()
    description = models.TextField(blank=True, default='')
    image_url = models.CharField(max_length=500, blank=True, default='')
    unit = models.CharField(max_length=50)
    is_fractional_allowed = models.BooleanField(default=False)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_product'
        ordering = ['name']
        constraints = [
            UniqueConstraint(fields=['sku'], name='uniq_product_sku'),
            CheckConstraint(check=~Q(sku=''), name='product_sku_non_empty'),
            CheckConstraint(check=~Q(name=''), name='product_name_non_empty'),
            CheckConstraint(check=~Q(unit=''), name='product_unit_non_empty'),
            CheckConstraint(check=Q(price_halalas__gte=0), name='product_price_halalas_gte_0'),
        ]
        indexes = [
            Index(fields=['category', 'is_active'], name='idx_product_category_active'),
        ]

    def clean(self):
        super().clean()
        if not self.sku or not self.sku.strip():
            raise ValidationError({'sku': 'Product SKU cannot be empty.'})
        if not self.name or not self.name.strip():
            raise ValidationError({'name': 'Product name cannot be empty.'})
        if not self.unit or not self.unit.strip():
            raise ValidationError({'unit': 'Product unit cannot be empty.'})
        if self.price_halalas is None or not isinstance(self.price_halalas, int) or isinstance(self.price_halalas, bool):
            raise ValidationError({'price_halalas': 'price_halalas must be an integer.'})
        if self.price_halalas < 0:
            raise ValidationError({'price_halalas': 'price_halalas must be non-negative (>= 0).'})

    def get_effective_price_halalas(self, branch: Branch | None = None) -> int:
        """
        Resolves the authoritative price in halalas for a given branch, consulting BranchProductOverride if present.
        """
        if branch is not None:
            override = self.branch_overrides.filter(branch=branch).first()
            if override is not None:
                return override.effective_price_halalas
        return self.price_halalas

    def is_available_in_branch(self, branch: Branch | None = None) -> bool:
        """
        Resolves whether this product is active and available in the given branch.
        """
        if not self.is_active:
            return False
        if branch is not None:
            if not branch.is_active:
                return False
            override = self.branch_overrides.filter(branch=branch).first()
            if override is not None:
                return bool(override.is_available)
        return True

    def __str__(self):
        return f"{self.name} ({self.sku})"


class BranchProductOverride(models.Model):
    """
    Optional branch-specific pricing, availability, and order quantity bounds for a Product.
    """
    branch = models.ForeignKey(
        Branch,
        on_delete=models.PROTECT,
        related_name='product_overrides',
    )
    product = models.ForeignKey(
        Product,
        on_delete=models.PROTECT,
        related_name='branch_overrides',
    )
    override_price_halalas = models.BigIntegerField(null=True, blank=True)
    is_available = models.BooleanField(default=True)
    min_order_quantity_milli = models.BigIntegerField(default=MILLI_UNITS_PER_UNIT)
    max_order_quantity_milli = models.BigIntegerField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_branchproductoverride'
        ordering = ['branch', 'product']
        constraints = [
            UniqueConstraint(fields=['branch', 'product'], name='uniq_branch_product_override'),
            CheckConstraint(
                check=Q(override_price_halalas__isnull=True) | Q(override_price_halalas__gte=0),
                name='branch_override_price_halalas_gte_0',
            ),
            CheckConstraint(
                check=Q(min_order_quantity_milli__gt=0),
                name='branch_override_min_qty_milli_gt_0',
            ),
            CheckConstraint(
                check=Q(max_order_quantity_milli__isnull=True)
                | Q(max_order_quantity_milli__gte=F('min_order_quantity_milli')),
                name='branch_override_max_gte_min_qty_milli',
            ),
        ]
        indexes = [
            Index(fields=['branch', 'is_available'], name='idx_branch_override_avail'),
        ]

    @property
    def effective_price_halalas(self) -> int:
        return resolve_effective_price_halalas(
            self.product.price_halalas,
            self.override_price_halalas,
        )

    @property
    def is_effectively_available(self) -> bool:
        return bool(self.is_available and self.product.is_active and self.branch.is_active)

    def clean(self):
        super().clean()
        if self.override_price_halalas is not None and self.override_price_halalas < 0:
            raise ValidationError({'override_price_halalas': 'override_price_halalas must be non-negative.'})
        if self.min_order_quantity_milli is None or self.min_order_quantity_milli <= 0:
            raise ValidationError({'min_order_quantity_milli': 'min_order_quantity_milli must be strictly positive.'})
        if (
            self.max_order_quantity_milli is not None
            and self.max_order_quantity_milli < self.min_order_quantity_milli
        ):
            raise ValidationError({
                'max_order_quantity_milli': 'max_order_quantity_milli must be >= min_order_quantity_milli.'
            })

    def __str__(self):
        return f"Override({self.branch.code}, {self.product.sku})"


class WarehouseStockBalance(models.Model):
    """
    Authoritative stock balance per (warehouse, product) in integer milli-units (1000 = 1.000 unit).
    Enforces non-negative stock and reserved_milli <= on_hand_milli at the database level.
    """
    warehouse = models.ForeignKey(
        Warehouse,
        on_delete=models.PROTECT,
        related_name='stock_balances',
    )
    product = models.ForeignKey(
        Product,
        on_delete=models.PROTECT,
        related_name='warehouse_stock_balances',
    )
    on_hand_milli = models.BigIntegerField(default=0)
    reserved_milli = models.BigIntegerField(default=0)
    version = models.BigIntegerField(default=1)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'products_warehousestockbalance'
        ordering = ['warehouse', 'product']
        constraints = [
            UniqueConstraint(
                fields=['warehouse', 'product'],
                name='uniq_warehouse_product_stock_balance',
            ),
            CheckConstraint(
                check=Q(on_hand_milli__gte=0),
                name='stock_balance_on_hand_milli_gte_0',
            ),
            CheckConstraint(
                check=Q(reserved_milli__gte=0),
                name='stock_balance_reserved_milli_gte_0',
            ),
            CheckConstraint(
                check=Q(reserved_milli__lte=F('on_hand_milli')),
                name='stock_balance_reserved_lte_on_hand',
            ),
            CheckConstraint(
                check=Q(version__gte=1),
                name='stock_balance_version_gte_1',
            ),
        ]
        indexes = [
            Index(fields=['product', 'warehouse'], name='idx_stock_balance_prod_wh'),
        ]

    @property
    def available_milli(self) -> int:
        return self.on_hand_milli - self.reserved_milli

    def clean(self):
        super().clean()
        if self.on_hand_milli is None or self.on_hand_milli < 0:
            raise ValidationError({'on_hand_milli': 'on_hand_milli must be non-negative (>= 0).'})
        if self.reserved_milli is None or self.reserved_milli < 0:
            raise ValidationError({'reserved_milli': 'reserved_milli must be non-negative (>= 0).'})
        if self.reserved_milli > self.on_hand_milli:
            raise ValidationError({'reserved_milli': 'reserved_milli cannot exceed on_hand_milli.'})
        if self.version is None or self.version < 1:
            raise ValidationError({'version': 'version must be >= 1.'})

    def __str__(self):
        return f"Stock({self.warehouse.code}, {self.product.sku}: on_hand={self.on_hand_milli}, reserved={self.reserved_milli})"


class AppendOnlyQuerySet(models.QuerySet):
    """
    Prevents bulk QuerySet.update() and QuerySet.delete() from bypassing Model.save()/delete()
    on append-only audit ledger tables.
    """

    def update(self, **kwargs):
        raise ValidationError('Audit ledger records are append-only and cannot be updated via QuerySet.update().')

    def bulk_update(self, objs, fields, batch_size=None):
        raise ValidationError('Audit ledger records are append-only and cannot be updated via QuerySet.bulk_update().')

    def delete(self):
        raise ValidationError('Audit ledger records are append-only and cannot be deleted via QuerySet.delete().')

    def _raw_delete(self, using):
        raise ValidationError('Audit ledger records are append-only and cannot be raw-deleted.')


class AppendOnlyManager(models.Manager.from_queryset(AppendOnlyQuerySet)):
    pass


class StockMovement(models.Model):
    """
    Append-only audit ledger of all inventory movements in integer milli-units.
    Protected by Model.save()/delete(), AppendOnlyQuerySet, and database BEFORE UPDATE/DELETE triggers.
    """

    objects = AppendOnlyManager()

    class MovementType(models.TextChoices):
        INITIAL_SEED = 'INITIAL_SEED', 'Initial Seed'
        RESTOCK = 'RESTOCK', 'Restock'
        CHECKOUT_DEDUCTION = 'CHECKOUT_DEDUCTION', 'Checkout Deduction'
        CANCELLATION_RESTORATION = 'CANCELLATION_RESTORATION', 'Cancellation Restoration'
        MANUAL_ADJUSTMENT = 'MANUAL_ADJUSTMENT', 'Manual Adjustment'
        WASTAGE = 'WASTAGE', 'Wastage'
        TRANSFER_IN = 'TRANSFER_IN', 'Transfer In'
        TRANSFER_OUT = 'TRANSFER_OUT', 'Transfer Out'

    warehouse = models.ForeignKey(
        Warehouse,
        on_delete=models.PROTECT,
        related_name='stock_movements',
    )
    product = models.ForeignKey(
        Product,
        on_delete=models.PROTECT,
        related_name='stock_movements',
    )
    movement_type = models.CharField(
        max_length=40,
        choices=MovementType.choices,
    )
    quantity_delta_milli = models.BigIntegerField()
    balance_after_milli = models.BigIntegerField()
    reference_order = models.ForeignKey(
        'orders.Order',
        null=True,
        blank=True,
        on_delete=models.PROTECT,
        related_name='stock_movements',
    )
    actor_id = models.CharField(max_length=100, blank=True, default='')
    reason = models.TextField()
    idempotency_key = models.CharField(max_length=128, null=True, blank=True, unique=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'products_stockmovement'
        ordering = ['-created_at', '-id']
        constraints = [
            CheckConstraint(
                check=~Q(quantity_delta_milli=0),
                name='stock_movement_delta_milli_non_zero',
            ),
            CheckConstraint(
                check=Q(balance_after_milli__gte=0),
                name='stock_movement_balance_after_milli_gte_0',
            ),
            CheckConstraint(
                check=~Q(reason=''),
                name='stock_movement_reason_non_empty',
            ),
        ]
        indexes = [
            Index(fields=['warehouse', 'product', '-created_at'], name='idx_stock_move_wh_prod_time'),
            Index(fields=['reference_order'], name='idx_stock_move_ref_order'),
        ]

    def clean(self):
        super().clean()
        if self.quantity_delta_milli is None or self.quantity_delta_milli == 0:
            raise ValidationError({'quantity_delta_milli': 'quantity_delta_milli must be non-zero.'})
        if self.balance_after_milli is None or self.balance_after_milli < 0:
            raise ValidationError({'balance_after_milli': 'balance_after_milli must be non-negative (>= 0).'})
        if not self.reason or not self.reason.strip():
            raise ValidationError({'reason': 'StockMovement reason cannot be empty.'})

    def save(self, *args, **kwargs):
        if self.pk is not None and not self._state.adding:
            raise ValidationError('StockMovement ledger is append-only; existing movement records cannot be modified.')
        self.full_clean()
        return super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise ValidationError('StockMovement ledger is append-only; movement records cannot be deleted.')

    def __str__(self):
        return (
            f"StockMovement({self.movement_type}, wh={self.warehouse_id}, "
            f"prod={self.product_id}, delta={self.quantity_delta_milli}, after={self.balance_after_milli})"
        )
