from django.core.exceptions import ValidationError
from django.db import models
from django.db.models import CheckConstraint, F, Index, Q, UniqueConstraint

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


def calculate_line_total_halalas(quantity_milli: int, unit_price_halalas: int) -> int:
    """
    Computes exact line-item total in integer SAR halalas from fractional milli-units (1000 = 1.000 unit)
    and unit price in halalas per 1000 milli-units using half-up integer arithmetic:
        (quantity_milli * unit_price_halalas + 500) // 1000
    Never uses floating-point arithmetic.
    """
    if not isinstance(quantity_milli, int) or isinstance(quantity_milli, bool):
        raise ValueError("quantity_milli must be an integer representing milli-units.")
    if quantity_milli <= 0:
        raise ValueError("quantity_milli must be strictly positive (> 0).")
    if not isinstance(unit_price_halalas, int) or isinstance(unit_price_halalas, bool):
        raise ValueError("unit_price_halalas must be an integer representing halalas.")
    if unit_price_halalas < 0:
        raise ValueError("unit_price_halalas must be non-negative (>= 0).")

    return (quantity_milli * unit_price_halalas + (MILLI_UNITS_PER_UNIT // 2)) // MILLI_UNITS_PER_UNIT


def calculate_order_total_halalas(line_totals_halalas: list[int] | tuple[int, ...]) -> int:
    """
    Computes the authoritative order total in integer SAR halalas from immutable item line totals.
    Never trusts client-submitted order totals.
    """
    total = 0
    for line_total in line_totals_halalas:
        if not isinstance(line_total, int) or isinstance(line_total, bool) or line_total < 0:
            raise ValueError("Each line_total_halalas must be a non-negative integer.")
        total += line_total
    return total


class Order(models.Model):
    """
    Multi-branch customer order with authoritative total in integer SAR halalas.
    """

    class OrderStatus(models.TextChoices):
        PENDING = 'PENDING', 'Pending'
        CONFIRMED = 'CONFIRMED', 'Confirmed'
        PROCESSING = 'PROCESSING', 'Processing'
        COMPLETED = 'COMPLETED', 'Completed'
        CANCELLED = 'CANCELLED', 'Cancelled'

    VALID_STATUS_TRANSITIONS = {
        OrderStatus.PENDING: frozenset({OrderStatus.CONFIRMED, OrderStatus.CANCELLED}),
        OrderStatus.CONFIRMED: frozenset({OrderStatus.PROCESSING, OrderStatus.CANCELLED}),
        OrderStatus.PROCESSING: frozenset({OrderStatus.COMPLETED, OrderStatus.CANCELLED}),
        OrderStatus.COMPLETED: frozenset(),
        OrderStatus.CANCELLED: frozenset(),
    }

    branch = models.ForeignKey(
        'products.Branch',
        on_delete=models.PROTECT,
        related_name='orders',
    )
    fulfillment_warehouse = models.ForeignKey(
        'products.Warehouse',
        null=True,
        blank=True,
        on_delete=models.PROTECT,
        related_name='fulfilled_orders',
    )
    customer_id = models.CharField(max_length=100, db_index=True)
    status = models.CharField(
        max_length=50,
        choices=OrderStatus.choices,
        default=OrderStatus.PENDING,
    )
    # Using BigIntegerField for halalas; computed server-side from OrderItem line totals
    total_halalas = models.BigIntegerField(default=0)
    idempotency_key = models.CharField(max_length=128, null=True, blank=True, unique=True)
    cancellation_restored = models.BooleanField(default=False)
    timestamp = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'orders_order'
        ordering = ['-timestamp', '-id']
        constraints = [
            CheckConstraint(
                check=Q(total_halalas__gte=0),
                name='order_total_halalas_gte_0',
            ),
            CheckConstraint(
                check=~Q(customer_id=''),
                name='order_customer_id_non_empty',
            ),
            CheckConstraint(
                check=Q(
                    status__in=[
                        'PENDING',
                        'CONFIRMED',
                        'PROCESSING',
                        'COMPLETED',
                        'CANCELLED',
                    ]
                ),
                name='order_status_valid_choice',
            ),
        ]
        indexes = [
            Index(fields=['branch', 'status', '-timestamp'], name='idx_order_branch_status_time'),
            Index(fields=['customer_id', '-timestamp'], name='idx_order_customer_time'),
        ]

    @classmethod
    def can_transition(cls, from_status: str, to_status: str) -> bool:
        allowed = cls.VALID_STATUS_TRANSITIONS.get(from_status, frozenset())
        return to_status in allowed

    def calculate_authoritative_total_halalas(self) -> int:
        if not self.pk:
            return 0
        line_totals = list(self.items.values_list('line_total_halalas', flat=True))
        return calculate_order_total_halalas(line_totals)

    def sync_total_halalas(self, *, save: bool = True) -> int:
        self.total_halalas = self.calculate_authoritative_total_halalas()
        if save and self.pk:
            self.save(update_fields=['total_halalas', 'updated_at'])
        return self.total_halalas

    def clean(self):
        super().clean()
        if not self.customer_id or not self.customer_id.strip():
            raise ValidationError({'customer_id': 'customer_id cannot be empty.'})
        if self.total_halalas is None or not isinstance(self.total_halalas, int) or isinstance(self.total_halalas, bool):
            raise ValidationError({'total_halalas': 'total_halalas must be an integer.'})
        if self.total_halalas < 0:
            raise ValidationError({'total_halalas': 'total_halalas must be non-negative (>= 0).'})
        if self.fulfillment_warehouse_id and self.branch_id:
            if self.fulfillment_warehouse.branch_id != self.branch_id:
                raise ValidationError({
                    'fulfillment_warehouse': 'Fulfillment warehouse must belong to the order branch.'
                })

    def __str__(self):
        return f"Order #{self.pk} ({self.status}, {self.total_halalas} halalas)"


class OrderItem(models.Model):
    """
    Immutable historical line-item snapshot belonging to an Order.
    Preserves product_name_snapshot, unit_snapshot, quantity_milli, unit_price_halalas, and line_total_halalas
    so subsequent catalog edits never alter historical orders.
    Protected by Model.save()/delete(), AppendOnlyQuerySet, and database BEFORE UPDATE/DELETE triggers.
    """
    objects = AppendOnlyManager()

    order = models.ForeignKey(
        Order,
        related_name='items',
        on_delete=models.PROTECT,
    )
    product = models.ForeignKey(
        'products.Product',
        on_delete=models.PROTECT,
        related_name='order_items',
    )
    product_name_snapshot = models.CharField(max_length=200)
    unit_snapshot = models.CharField(max_length=50)
    quantity_milli = models.BigIntegerField()
    unit_price_halalas = models.BigIntegerField()
    line_total_halalas = models.BigIntegerField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'orders_orderitem'
        ordering = ['id']
        constraints = [
            CheckConstraint(
                check=Q(quantity_milli__gt=0),
                name='order_item_quantity_milli_gt_0',
            ),
            CheckConstraint(
                check=Q(unit_price_halalas__gte=0),
                name='order_item_unit_price_halalas_gte_0',
            ),
            CheckConstraint(
                check=Q(line_total_halalas__gte=0),
                name='order_item_line_total_halalas_gte_0',
            ),
            CheckConstraint(
                check=~Q(product_name_snapshot=''),
                name='order_item_prod_name_snap_non_empty',
            ),
            CheckConstraint(
                check=~Q(unit_snapshot=''),
                name='order_item_unit_snap_non_empty',
            ),
        ]
        indexes = [
            Index(fields=['order'], name='idx_order_item_order'),
            Index(fields=['product'], name='idx_order_item_product'),
        ]

    @property
    def price_halalas(self) -> int:
        """Backward-compatible read property aliasing unit_price_halalas."""
        return self.unit_price_halalas

    @staticmethod
    def compute_line_total_halalas(quantity_milli: int, unit_price_halalas: int) -> int:
        return calculate_line_total_halalas(quantity_milli, unit_price_halalas)

    def populate_snapshots_from_catalog(self, branch: Branch | None = None) -> None:
        """
        Populates immutable snapshot fields from the authoritative Product and optional BranchProductOverride
        at order creation time, and computes line_total_halalas server-side.
        """
        if self.product_id is not None:
            if not self.product_name_snapshot:
                self.product_name_snapshot = self.product.name
            if not self.unit_snapshot:
                self.unit_snapshot = self.product.unit
            if self.unit_price_halalas is None:
                target_branch = branch or (self.order.branch if self.order_id else None)
                self.unit_price_halalas = self.product.get_effective_price_halalas(branch=target_branch)
        if self.quantity_milli is not None and self.unit_price_halalas is not None:
            self.line_total_halalas = calculate_line_total_halalas(
                self.quantity_milli,
                self.unit_price_halalas,
            )

    def clean(self):
        super().clean()
        if self.quantity_milli is None or not isinstance(self.quantity_milli, int) or isinstance(self.quantity_milli, bool):
            raise ValidationError({'quantity_milli': 'quantity_milli must be an integer in milli-units.'})
        if self.quantity_milli <= 0:
            raise ValidationError({'quantity_milli': 'quantity_milli must be strictly positive (> 0).'})
        if self.product_id is not None and not self.product.is_fractional_allowed:
            if self.quantity_milli % MILLI_UNITS_PER_UNIT != 0:
                raise ValidationError({
                    'quantity_milli': 'Fractional quantity is not allowed for this product.'
                })
        if (
            self.unit_price_halalas is None
            or not isinstance(self.unit_price_halalas, int)
            or isinstance(self.unit_price_halalas, bool)
            or self.unit_price_halalas < 0
        ):
            raise ValidationError({'unit_price_halalas': 'unit_price_halalas must be a non-negative integer.'})

        expected_line_total = calculate_line_total_halalas(self.quantity_milli, self.unit_price_halalas)
        if self.line_total_halalas is None:
            self.line_total_halalas = expected_line_total
        elif self.line_total_halalas != expected_line_total:
            raise ValidationError({
                'line_total_halalas': (
                    f'line_total_halalas ({self.line_total_halalas}) does not match authoritative calculation '
                    f'({expected_line_total}) for quantity_milli={self.quantity_milli} '
                    f'and unit_price_halalas={self.unit_price_halalas}.'
                )
            })
        if not self.product_name_snapshot or not self.product_name_snapshot.strip():
            raise ValidationError({'product_name_snapshot': 'product_name_snapshot cannot be empty.'})
        if not self.unit_snapshot or not self.unit_snapshot.strip():
            raise ValidationError({'unit_snapshot': 'unit_snapshot cannot be empty.'})

    def save(self, *args, **kwargs):
        if self.pk is not None and not self._state.adding:
            raise ValidationError('OrderItem snapshots are immutable and cannot be modified after creation.')
        self.populate_snapshots_from_catalog()
        self.full_clean()
        return super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise ValidationError('OrderItem historical snapshots cannot be deleted.')

    def __str__(self):
        return (
            f"OrderItem(order={self.order_id}, product={self.product_name_snapshot}, "
            f"qty_milli={self.quantity_milli}, line_total={self.line_total_halalas})"
        )


class OrderStatusHistory(models.Model):
    """
    Append-only audit trail of order status transitions.
    Protected by Model.save()/delete(), AppendOnlyQuerySet, and database BEFORE UPDATE/DELETE triggers.
    """
    objects = AppendOnlyManager()

    order = models.ForeignKey(
        Order,
        related_name='status_history',
        on_delete=models.PROTECT,
    )
    from_status = models.CharField(
        max_length=50,
        choices=Order.OrderStatus.choices,
        null=True,
        blank=True,
    )
    to_status = models.CharField(
        max_length=50,
        choices=Order.OrderStatus.choices,
    )
    changed_by_actor_id = models.CharField(max_length=100, blank=True, default='')
    reason = models.TextField(blank=True, default='')
    changed_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'orders_orderstatushistory'
        ordering = ['changed_at', 'id']
        constraints = [
            CheckConstraint(
                check=Q(
                    to_status__in=[
                        'PENDING',
                        'CONFIRMED',
                        'PROCESSING',
                        'COMPLETED',
                        'CANCELLED',
                    ]
                ),
                name='order_status_hist_to_valid',
            ),
            CheckConstraint(
                check=Q(from_status__isnull=True) | ~Q(from_status=F('to_status')),
                name='order_status_hist_changed',
            ),
        ]
        indexes = [
            Index(fields=['order', '-changed_at'], name='idx_order_status_hist_time'),
        ]

    def clean(self):
        super().clean()
        if self.from_status is not None and self.from_status == self.to_status:
            raise ValidationError({'to_status': 'to_status must differ from from_status.'})
        if self.from_status is not None and not Order.can_transition(self.from_status, self.to_status):
            raise ValidationError({
                'to_status': f'Invalid order status transition from {self.from_status} to {self.to_status}.'
            })

    def save(self, *args, **kwargs):
        if self.pk is not None and not self._state.adding:
            raise ValidationError('OrderStatusHistory records are append-only and cannot be modified.')
        self.full_clean()
        return super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise ValidationError('OrderStatusHistory records are append-only and cannot be deleted.')

    def __str__(self):
        return f"OrderStatusHistory(order={self.order_id}: {self.from_status} -> {self.to_status})"


class IdempotencyKey(models.Model):
    """
    Persistent deduplication key for order creation, cancellation, and stock-changing operations.
    Uniquely scoped by (actor_id, operation, idempotency_key).
    """
    actor_id = models.CharField(max_length=100)
    operation = models.CharField(max_length=64)
    idempotency_key = models.CharField(max_length=128)
    request_hash = models.CharField(max_length=64)
    response_status_code = models.IntegerField(null=True, blank=True)
    response_payload = models.JSONField(null=True, blank=True)
    reference_order = models.ForeignKey(
        Order,
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='idempotency_records',
    )
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = 'orders_idempotencykey'
        ordering = ['-created_at']
        constraints = [
            UniqueConstraint(
                fields=['actor_id', 'operation', 'idempotency_key'],
                name='uniq_actor_operation_idempotency_key',
            ),
            CheckConstraint(
                check=~Q(actor_id=''),
                name='idempotency_actor_id_non_empty',
            ),
            CheckConstraint(
                check=~Q(operation=''),
                name='idempotency_operation_non_empty',
            ),
            CheckConstraint(
                check=~Q(idempotency_key=''),
                name='idempotency_key_non_empty',
            ),
            CheckConstraint(
                check=~Q(request_hash=''),
                name='idempotency_req_hash_non_empty',
            ),
        ]
        indexes = [
            Index(fields=['expires_at'], name='idx_idempotency_expires_at'),
        ]

    def clean(self):
        super().clean()
        if not self.actor_id or not self.actor_id.strip():
            raise ValidationError({'actor_id': 'actor_id cannot be empty.'})
        if not self.operation or not self.operation.strip():
            raise ValidationError({'operation': 'operation cannot be empty.'})
        if not self.idempotency_key or not self.idempotency_key.strip():
            raise ValidationError({'idempotency_key': 'idempotency_key cannot be empty.'})
        if not self.request_hash or not self.request_hash.strip():
            raise ValidationError({'request_hash': 'request_hash cannot be empty.'})

    def __str__(self):
        return f"IdempotencyKey({self.actor_id}, {self.operation}, {self.idempotency_key})"


class UserIdentityProfile(models.Model):
    """
    Authoritative server-side mapping between an authenticated Django User and a domain
    customer/staff identity (CUSTOMER, BRANCH_MANAGER, or OWNER).
    Client-supplied role, customer_id, store_id, or branch_id values are never trusted;
    authorization is always resolved from this server-side model and active BranchRoleAssignment rows.
    """

    class Role(models.TextChoices):
        CUSTOMER = 'CUSTOMER', 'Customer'
        BRANCH_MANAGER = 'BRANCH_MANAGER', 'Branch Manager'
        OWNER = 'OWNER', 'Store Owner'

    user = models.OneToOneField(
        'auth.User',
        on_delete=models.PROTECT,
        related_name='identity_profile',
    )
    role = models.CharField(
        max_length=32,
        choices=Role.choices,
        default=Role.CUSTOMER,
    )
    customer_id = models.CharField(
        max_length=100,
        null=True,
        blank=True,
        unique=True,
    )
    store = models.ForeignKey(
        'products.Store',
        null=True,
        blank=True,
        on_delete=models.PROTECT,
        related_name='identity_profiles',
    )
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'orders_useridentityprofile'
        ordering = ['id']
        constraints = [
            CheckConstraint(
                check=Q(role__in=['CUSTOMER', 'BRANCH_MANAGER', 'OWNER']),
                name='identity_profile_role_valid',
            ),
            CheckConstraint(
                check=Q(customer_id__isnull=True) | ~Q(customer_id=''),
                name='identity_profile_cust_id_non_empty',
            ),
            CheckConstraint(
                check=~Q(role='CUSTOMER') | (Q(customer_id__isnull=False) & ~Q(customer_id='')),
                name='identity_profile_customer_requires_cust_id',
            ),
            CheckConstraint(
                check=~Q(role__in=['BRANCH_MANAGER', 'OWNER']) | Q(store__isnull=False),
                name='identity_profile_staff_requires_store',
            ),
        ]
        indexes = [
            Index(fields=['role', 'is_active'], name='idx_identity_role_active'),
            Index(fields=['store', 'role'], name='idx_identity_store_role'),
        ]

    def clean(self):
        super().clean()
        if self.role not in {
            self.Role.CUSTOMER,
            self.Role.BRANCH_MANAGER,
            self.Role.OWNER,
        }:
            raise ValidationError({'role': f'Unsupported identity role: {self.role}'})

        if self.role == self.Role.CUSTOMER:
            if not self.customer_id or not self.customer_id.strip():
                raise ValidationError({
                    'customer_id': 'CUSTOMER identity profile requires a non-empty server-side customer_id.'
                })
        elif self.customer_id is not None and not self.customer_id.strip():
            raise ValidationError({'customer_id': 'customer_id cannot be an empty string.'})

        if self.role in (self.Role.BRANCH_MANAGER, self.Role.OWNER):
            if self.store_id is None:
                raise ValidationError({
                    'store': f'{self.role} identity profile must be bound to a Store.'
                })

    def save(self, *args, **kwargs):
        self.full_clean()
        return super().save(*args, **kwargs)

    def __str__(self):
        return f"UserIdentityProfile(user={self.user_id}, role={self.role}, customer_id={self.customer_id})"


class BranchRoleAssignment(models.Model):
    """
    Server-side assignment granting a staff UserIdentityProfile (such as BRANCH_MANAGER)
    authorization for a specific Branch within their Store.
    """
    profile = models.ForeignKey(
        UserIdentityProfile,
        on_delete=models.PROTECT,
        related_name='branch_assignments',
    )
    branch = models.ForeignKey(
        'products.Branch',
        on_delete=models.PROTECT,
        related_name='role_assignments',
    )
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'orders_branchroleassignment'
        ordering = ['profile', 'branch']
        constraints = [
            UniqueConstraint(
                fields=['profile', 'branch'],
                name='uniq_profile_branch_assignment',
            ),
        ]
        indexes = [
            Index(fields=['profile', 'is_active'], name='idx_branch_assign_prof_active'),
            Index(fields=['branch', 'is_active'], name='idx_branch_assign_br_active'),
        ]

    def clean(self):
        super().clean()
        if self.profile_id and self.branch_id:
            if self.profile.store_id is not None and self.branch.store_id != self.profile.store_id:
                raise ValidationError({
                    'branch': (
                        f"Assigned branch '{self.branch.code}' (store_id={self.branch.store_id}) "
                        f"does not belong to profile store (store_id={self.profile.store_id})."
                    )
                })

    def save(self, *args, **kwargs):
        self.full_clean()
        return super().save(*args, **kwargs)

    def __str__(self):
        return f"BranchRoleAssignment(profile={self.profile_id}, branch={self.branch_id}, active={self.is_active})"


class AuthSessionToken(models.Model):
    """
    Server-side authentication session token record with explicit access and refresh token
    expiration, single-use rotation lineage (`token_family_id`, `replaced_by`), and immediate
    revocation tracking.
    Raw bearer/refresh tokens are never stored in plaintext; only SHA-256 hex digests are persisted.
    """

    class RevocationReason(models.TextChoices):
        LOGOUT = 'LOGOUT', 'Logged Out'
        ROTATED = 'ROTATED', 'Rotated on Renewal'
        REUSE_DETECTED = 'REUSE_DETECTED', 'Refresh Token Reuse Detected'
        EXPIRED = 'EXPIRED', 'Token Expired'
        ACCOUNT_INACTIVE = 'ACCOUNT_INACTIVE', 'Account or Profile Inactive'
        REVOKED = 'REVOKED', 'Explicitly Revoked'

    user = models.ForeignKey(
        'auth.User',
        on_delete=models.CASCADE,
        related_name='auth_session_tokens',
    )
    token_family_id = models.CharField(max_length=64, db_index=True)
    access_token_hash = models.CharField(max_length=64, unique=True)
    refresh_token_hash = models.CharField(max_length=64, unique=True)
    access_expires_at = models.DateTimeField(db_index=True)
    refresh_expires_at = models.DateTimeField(db_index=True)
    revoked_at = models.DateTimeField(null=True, blank=True, db_index=True)
    revocation_reason = models.CharField(max_length=64, blank=True, default='')
    replaced_by = models.OneToOneField(
        'self',
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name='replaces_token',
    )
    created_at = models.DateTimeField(auto_now_add=True)
    last_used_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = 'orders_authsessiontoken'
        ordering = ['-created_at', '-id']
        constraints = [
            CheckConstraint(
                check=~Q(token_family_id=''),
                name='auth_token_family_non_empty',
            ),
            CheckConstraint(
                check=~Q(access_token_hash=''),
                name='auth_access_hash_non_empty',
            ),
            CheckConstraint(
                check=~Q(refresh_token_hash=''),
                name='auth_refresh_hash_non_empty',
            ),
            CheckConstraint(
                check=~Q(access_token_hash=F('refresh_token_hash')),
                name='auth_access_ne_refresh_hash',
            ),
            CheckConstraint(
                check=Q(refresh_expires_at__gte=F('access_expires_at')),
                name='auth_refresh_gte_access_exp',
            ),
        ]
        indexes = [
            Index(fields=['user', 'revoked_at'], name='idx_auth_token_user_revoked'),
            Index(fields=['token_family_id', 'revoked_at'], name='idx_auth_token_family_revoked'),
        ]

    def clean(self):
        super().clean()
        if not self.token_family_id or not self.token_family_id.strip():
            raise ValidationError({'token_family_id': 'token_family_id cannot be empty.'})
        if not self.access_token_hash or not self.access_token_hash.strip():
            raise ValidationError({'access_token_hash': 'access_token_hash cannot be empty.'})
        if not self.refresh_token_hash or not self.refresh_token_hash.strip():
            raise ValidationError({'refresh_token_hash': 'refresh_token_hash cannot be empty.'})
        if self.access_token_hash == self.refresh_token_hash:
            raise ValidationError({'refresh_token_hash': 'refresh_token_hash must differ from access_token_hash.'})
        if self.access_expires_at and self.refresh_expires_at:
            if self.refresh_expires_at < self.access_expires_at:
                raise ValidationError({
                    'refresh_expires_at': 'refresh_expires_at must be greater than or equal to access_expires_at.'
                })

    def save(self, *args, **kwargs):
        self.full_clean()
        return super().save(*args, **kwargs)

    def __str__(self):
        return (
            f"AuthSessionToken(user={self.user_id}, family={self.token_family_id}, "
            f"revoked={self.revoked_at is not None})"
        )


class AuthThrottleEvent(models.Model):
    """
    Database-backed rate-limiting and failed-login tracking record for authentication endpoints.
    """

    class Scope(models.TextChoices):
        LOGIN_REQUEST = 'LOGIN_REQUEST', 'Login Endpoint Request'
        LOGIN_FAILURE = 'LOGIN_FAILURE', 'Failed Login Attempt'
        TOKEN_REFRESH_REQUEST = 'TOKEN_REFRESH_REQUEST', 'Token Refresh Endpoint Request'

    scope = models.CharField(max_length=40, choices=Scope.choices)
    identifier = models.CharField(max_length=255)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)

    class Meta:
        db_table = 'orders_auththrottleevent'
        ordering = ['-created_at', '-id']
        constraints = [
            CheckConstraint(
                check=Q(
                    scope__in=[
                        'LOGIN_REQUEST',
                        'LOGIN_FAILURE',
                        'TOKEN_REFRESH_REQUEST',
                    ]
                ),
                name='auth_throttle_scope_valid',
            ),
            CheckConstraint(
                check=~Q(identifier=''),
                name='auth_throttle_ident_non_empty',
            ),
        ]
        indexes = [
            Index(fields=['scope', 'identifier', '-created_at'], name='idx_auth_throttle_lookup'),
        ]

    def __str__(self):
        return f"AuthThrottleEvent(scope={self.scope}, identifier={self.identifier})"


