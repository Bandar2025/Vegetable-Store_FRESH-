"""
Standalone HTTP contract server for integration testing between the native Android
client (Retrofit + OkHttp + FreshAuthInterceptor + LocalAuthRepository) and the
actual Python authentication/JWT/registration & guest catalog logic in
`apps.orders.auth` and `apps.products.views` backed by SQLite3.

Can be used both in-process in Python tests and as a subprocess from Android JVM
integration tests (`PYTHONPATH=backend python3 -m apps.orders.test_http_server`).
"""

from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import sqlite3
import sys
import threading
from urllib.parse import parse_qs, urlparse

from apps.orders.auth import (
    GENERIC_INVALID_ACCESS_TOKEN_MESSAGE,
    GENERIC_INVALID_CREDENTIALS_MESSAGE,
    GENERIC_INVALID_REFRESH_TOKEN_MESSAGE,
    CustomerRegistrationValidationError,
    authenticate_access_token_sqlite,
    enforce_no_client_identity_spoofing,
    ensure_sqlite_auth_schema,
    hash_password_pbkdf2_sha256,
    login_with_credentials_sqlite,
    register_customer_account,
    revoke_token_session_sqlite,
    rotate_session_token_sqlite,
)
from apps.orders.services import (
    FORBIDDEN_ITEM_FIELDS,
    FORBIDDEN_ORDER_CHECKOUT_FIELDS,
    AuthenticationBoundaryError,
    CheckoutResourceNotFoundError,
    CheckoutValidationError,
    IdempotencyConflictError,
    InsufficientStockError,
    ServiceActor,
    SpoofedFinancialDataError,
    checkout_order,
    install_sqlite_append_only_triggers,
    list_orders_for_actor_sqlite,
)
from middleware.auth_rate_limit import HttpRequest


def list_public_or_branch_categories_sqlite(
    conn: sqlite3.Connection,
    actor: ServiceActor | None = None,
    query_params: dict[str, str] | None = None,
    headers: dict[str, str] | None = None,
) -> list[dict]:
    cur = conn.execute(
        """
        SELECT id, name, display_order, is_active
        FROM products_category
        WHERE is_active = 1
        ORDER BY display_order ASC, id ASC
        """
    )
    return [
        {
            'id': int(row[0]),
            'name': str(row[1]),
            'display_order': int(row[2]),
            'is_active': bool(row[3]),
        }
        for row in cur.fetchall()
    ]


def list_public_or_branch_products_sqlite(
    conn: sqlite3.Connection,
    actor: ServiceActor | None = None,
    query_params: dict[str, str] | None = None,
    headers: dict[str, str] | None = None,
) -> list[dict]:
    cur = conn.execute(
        """
        SELECT
            p.id, p.sku, p.barcode, p.name,
            c.id, c.name, c.display_order, c.is_active,
            p.price_halalas, p.description, p.image_url, p.unit,
            p.is_fractional_allowed, p.is_active
        FROM products_product p
        JOIN products_category c ON c.id = p.category_id
        WHERE p.is_active = 1 AND c.is_active = 1
        ORDER BY p.id ASC
        """
    )
    return [
        {
            'id': int(row[0]),
            'sku': str(row[1]),
            'barcode': row[2],
            'name': str(row[3]),
            'category': {
                'id': int(row[4]),
                'name': str(row[5]),
                'display_order': int(row[6]),
                'is_active': bool(row[7]),
            },
            'price_halalas': int(row[8]),
            'effective_price_halalas': int(row[8]),
            'is_available_in_branch': True,
            'description': str(row[9] or ''),
            'image_url': str(row[10] or ''),
            'unit': str(row[11]),
            'is_fractional_allowed': bool(row[12]),
            'is_active': bool(row[13]),
        }
        for row in cur.fetchall()
    ]


class _SimpleRequestAdapter:
    def __init__(
        self,
        method: str,
        path: str,
        headers: dict[str, str],
        query_params: dict[str, str],
        data: dict,
        raw_body: bytes,
        client_ip: str = '127.0.0.1',
    ):
        self.method = method.upper()
        self.path = path
        self.path_info = path
        self.headers = headers
        self.query_params = query_params
        self.data = data
        self.body = raw_body
        meta: dict[str, str] = {'REMOTE_ADDR': client_ip}
        for k, v in headers.items():
            meta['HTTP_' + k.upper().replace('-', '_')] = v
        self.META = meta
        self._request = HttpRequest(method=self.method, path=path, meta=meta, body=raw_body)
        self.user = None


def create_seeded_sqlite_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(':memory:', check_same_thread=False)
    conn.execute('PRAGMA foreign_keys = ON;')
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS products_store (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code VARCHAR(50) NOT NULL UNIQUE,
            name VARCHAR(200) NOT NULL,
            is_active BOOLEAN NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS products_branch (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            store_id INTEGER NOT NULL REFERENCES products_store(id) ON DELETE RESTRICT,
            code VARCHAR(50) NOT NULL,
            name VARCHAR(200) NOT NULL,
            is_active BOOLEAN NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS products_warehouse (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            code VARCHAR(50) NOT NULL,
            name VARCHAR(200) NOT NULL,
            is_primary_fulfillment BOOLEAN NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1,
            CONSTRAINT uniq_warehouse_code_per_branch UNIQUE (branch_id, code)
        );
        CREATE TABLE IF NOT EXISTS products_category (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name VARCHAR(100) NOT NULL UNIQUE,
            display_order INTEGER NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS products_product (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sku VARCHAR(100) NOT NULL UNIQUE,
            barcode VARCHAR(100) NULL UNIQUE,
            name VARCHAR(200) NOT NULL,
            category_id INTEGER NOT NULL REFERENCES products_category(id) ON DELETE RESTRICT,
            price_halalas INTEGER NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            image_url VARCHAR(500) NOT NULL DEFAULT '',
            unit VARCHAR(50) NOT NULL,
            is_fractional_allowed BOOLEAN NOT NULL DEFAULT 0,
            is_active BOOLEAN NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS products_branchproductoverride (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            override_price_halalas INTEGER NULL,
            is_available BOOLEAN NOT NULL DEFAULT 1,
            min_order_quantity_milli INTEGER NOT NULL DEFAULT 1000,
            max_order_quantity_milli INTEGER NULL,
            CONSTRAINT uniq_branch_product_override UNIQUE (branch_id, product_id)
        );
        CREATE TABLE IF NOT EXISTS products_warehousestockbalance (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            warehouse_id INTEGER NOT NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            on_hand_milli INTEGER NOT NULL DEFAULT 0,
            reserved_milli INTEGER NOT NULL DEFAULT 0,
            version INTEGER NOT NULL DEFAULT 1,
            CONSTRAINT uniq_warehouse_product_stock_balance UNIQUE (warehouse_id, product_id),
            CONSTRAINT stock_balance_on_hand_milli_gte_0 CHECK (on_hand_milli >= 0),
            CONSTRAINT stock_balance_reserved_milli_gte_0 CHECK (reserved_milli >= 0),
            CONSTRAINT stock_balance_reserved_lte_on_hand CHECK (reserved_milli <= on_hand_milli),
            CONSTRAINT stock_balance_version_gte_1 CHECK (version >= 1)
        );
        CREATE TABLE IF NOT EXISTS orders_order (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id INTEGER NOT NULL REFERENCES products_branch(id) ON DELETE RESTRICT,
            fulfillment_warehouse_id INTEGER NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            customer_id VARCHAR(100) NOT NULL,
            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
            total_halalas INTEGER NOT NULL DEFAULT 0,
            idempotency_key VARCHAR(128) NULL,
            cancellation_restored BOOLEAN NOT NULL DEFAULT 0,
            timestamp TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT uniq_order_customer_idempotency_key UNIQUE (customer_id, idempotency_key),
            CONSTRAINT order_total_halalas_gte_0 CHECK (total_halalas >= 0),
            CONSTRAINT order_customer_id_non_empty CHECK (customer_id <> ''),
            CONSTRAINT order_idempotency_key_non_empty CHECK (idempotency_key IS NULL OR idempotency_key <> ''),
            CONSTRAINT order_status_valid_choice CHECK (
                status IN ('PENDING', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED')
            )
        );
        CREATE TABLE IF NOT EXISTS orders_orderitem (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            order_id INTEGER NOT NULL REFERENCES orders_order(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            product_name_snapshot VARCHAR(200) NOT NULL,
            unit_snapshot VARCHAR(50) NOT NULL,
            quantity_milli INTEGER NOT NULL,
            unit_price_halalas INTEGER NOT NULL,
            line_total_halalas INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT order_item_quantity_milli_gt_0 CHECK (quantity_milli > 0),
            CONSTRAINT order_item_unit_price_halalas_gte_0 CHECK (unit_price_halalas >= 0),
            CONSTRAINT order_item_line_total_halalas_gte_0 CHECK (line_total_halalas >= 0),
            CONSTRAINT order_item_prod_name_snap_non_empty CHECK (product_name_snapshot <> ''),
            CONSTRAINT order_item_unit_snap_non_empty CHECK (unit_snapshot <> '')
        );
        CREATE TABLE IF NOT EXISTS orders_orderstatushistory (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            order_id INTEGER NOT NULL REFERENCES orders_order(id) ON DELETE RESTRICT,
            from_status VARCHAR(50) NULL,
            to_status VARCHAR(50) NOT NULL,
            changed_by_actor_id VARCHAR(100) NOT NULL DEFAULT '',
            reason TEXT NOT NULL DEFAULT '',
            changed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT order_status_hist_to_valid CHECK (
                to_status IN ('PENDING', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED')
            ),
            CONSTRAINT order_status_hist_changed CHECK (
                from_status IS NULL OR from_status <> to_status
            )
        );
        CREATE TABLE IF NOT EXISTS orders_idempotencykey (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            actor_id VARCHAR(100) NOT NULL,
            operation VARCHAR(64) NOT NULL,
            idempotency_key VARCHAR(128) NOT NULL,
            request_hash VARCHAR(64) NOT NULL,
            response_status_code INTEGER NULL,
            response_payload TEXT NULL,
            reference_order_id INTEGER NULL REFERENCES orders_order(id) ON DELETE SET NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            expires_at TEXT NULL,
            CONSTRAINT uniq_actor_operation_idempotency_key UNIQUE (actor_id, operation, idempotency_key),
            CONSTRAINT idempotency_actor_id_non_empty CHECK (actor_id <> ''),
            CONSTRAINT idempotency_operation_non_empty CHECK (operation <> ''),
            CONSTRAINT idempotency_key_non_empty CHECK (idempotency_key <> ''),
            CONSTRAINT idempotency_req_hash_non_empty CHECK (request_hash <> '')
        );
        CREATE TABLE IF NOT EXISTS products_stockmovement (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            warehouse_id INTEGER NOT NULL REFERENCES products_warehouse(id) ON DELETE RESTRICT,
            product_id INTEGER NOT NULL REFERENCES products_product(id) ON DELETE RESTRICT,
            movement_type VARCHAR(40) NOT NULL,
            quantity_delta_milli INTEGER NOT NULL,
            balance_after_milli INTEGER NOT NULL,
            reference_order_id INTEGER NULL REFERENCES orders_order(id) ON DELETE RESTRICT,
            actor_id VARCHAR(100) NOT NULL DEFAULT '',
            reason TEXT NOT NULL,
            idempotency_key VARCHAR(128) NULL UNIQUE,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT stock_movement_delta_milli_non_zero CHECK (quantity_delta_milli <> 0),
            CONSTRAINT stock_movement_balance_after_milli_gte_0 CHECK (balance_after_milli >= 0),
            CONSTRAINT stock_movement_reason_non_empty CHECK (reason <> '')
        );
        """
    )
    install_sqlite_append_only_triggers(conn)
    ensure_sqlite_auth_schema(conn)
    conn.execute(
        "INSERT INTO products_store (id, code, name, is_active) VALUES (1, 'FRESH-KSA', 'فريش السعودية', 1)"
    )
    conn.execute(
        "INSERT INTO products_branch (id, store_id, code, name, is_active) VALUES (1, 1, 'RUH-01', 'فرع الرياض', 1)"
    )
    conn.execute(
        "INSERT INTO products_warehouse (id, branch_id, code, name, is_primary_fulfillment, is_active) VALUES (1, 1, 'WH-RUH-01', 'مستودع الرياض الرئيسي', 1, 1)"
    )
    conn.execute(
        "INSERT INTO products_category (id, name, display_order, is_active) VALUES (1, 'خضروات طازجة', 1, 1)"
    )
    conn.execute(
        "INSERT INTO products_category (id, name, display_order, is_active) VALUES (2, 'فواكه موسمية', 2, 1)"
    )
    conn.execute(
        """
        INSERT INTO products_product (
            id, sku, barcode, name, category_id, price_halalas, description, image_url, unit, is_fractional_allowed, is_active
        ) VALUES (
            1, 'SKU-TOMATO-01', '628100000001', 'طماطم محمية طازجة', 1, 795, 'طماطم قطف اليوم', '', 'كيلو', 1, 1
        )
        """
    )
    conn.execute(
        """
        INSERT INTO products_product (
            id, sku, barcode, name, category_id, price_halalas, description, image_url, unit, is_fractional_allowed, is_active
        ) VALUES (
            2, 'SKU-APPLE-02', '628100000002', 'تفاح أحمر سكري', 2, 1150, 'تفاح طازج مستورد', '', 'كيلو', 1, 1
        )
        """
    )
    conn.execute(
        "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli, version) VALUES (1, 1, 50000, 0, 1)"
    )
    conn.execute(
        "INSERT INTO products_warehousestockbalance (warehouse_id, product_id, on_hand_milli, reserved_milli, version) VALUES (1, 2, 50000, 0, 1)"
    )

    # Seed pre-provisioned OWNER and BRANCH_MANAGER accounts for role-based login verification
    owner_pw = hash_password_pbkdf2_sha256('OwnerPass123')
    cur_owner = conn.execute(
        """
        INSERT INTO auth_user (password, is_superuser, username, first_name, last_name, email, is_staff, is_active)
        VALUES (?, 0, 'owner@fresh.sa', 'مالك', 'المتجر', 'owner@fresh.sa', 1, 1)
        """,
        (owner_pw,),
    )
    owner_user_id = int(cur_owner.lastrowid)
    conn.execute(
        """
        INSERT INTO orders_useridentityprofile (user_id, role, customer_id, store_id, is_active)
        VALUES (?, 'OWNER', NULL, 1, 1)
        """,
        (owner_user_id,),
    )

    manager_pw = hash_password_pbkdf2_sha256('ManagerPass123')
    cur_mgr = conn.execute(
        """
        INSERT INTO auth_user (password, is_superuser, username, first_name, last_name, email, is_staff, is_active)
        VALUES (?, 0, 'manager@fresh.sa', 'مشرف', 'الفرع', 'manager@fresh.sa', 1, 1)
        """,
        (manager_pw,),
    )
    mgr_user_id = int(cur_mgr.lastrowid)
    cur_mgr_prof = conn.execute(
        """
        INSERT INTO orders_useridentityprofile (user_id, role, customer_id, store_id, is_active)
        VALUES (?, 'BRANCH_MANAGER', NULL, 1, 1)
        """,
        (mgr_user_id,),
    )
    mgr_prof_id = int(cur_mgr_prof.lastrowid)
    conn.execute(
        """
        INSERT INTO orders_branchroleassignment (profile_id, branch_id, is_active)
        VALUES (?, 1, 1)
        """,
        (mgr_prof_id,),
    )

    conn.commit()
    return conn


class FreshContractHttpServer:
    def __init__(self, host: str = '127.0.0.1', port: int = 0):
        self.conn = create_seeded_sqlite_connection()
        self.lock = threading.Lock()
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args) -> None:
                # Silence noisy access logs during automated test runs
                return

            def _send_json(self, status_code: int, payload: object, extra_headers: dict[str, str] | None = None) -> None:
                raw = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(status_code)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(raw)))
                if extra_headers:
                    for hk, hv in extra_headers.items():
                        self.send_header(hk, hv)
                self.end_headers()
                self.wfile.write(raw)

            def _parse_request(self) -> tuple[str, dict[str, str], dict, bytes]:
                parsed_url = urlparse(self.path)
                path = parsed_url.path
                qs_raw = parse_qs(parsed_url.query)
                query_params = {k: v[0] for k, v in qs_raw.items() if v}
                content_len = int(self.headers.get('Content-Length', '0') or '0')
                raw_body = self.rfile.read(content_len) if content_len > 0 else b''
                data: dict = {}
                if raw_body:
                    try:
                        decoded = json.loads(raw_body.decode('utf-8'))
                        if isinstance(decoded, dict):
                            data = decoded
                    except Exception:
                        data = {}
                return path, query_params, data, raw_body

            def do_GET(self) -> None:
                self._handle('GET')

            def do_POST(self) -> None:
                self._handle('POST')

            def _handle(self, method: str) -> None:
                path, query_params, data, raw_body = self._parse_request()
                headers_dict = {k: v for k, v in self.headers.items()}
                req_adapter = _SimpleRequestAdapter(
                    method=method,
                    path=path,
                    headers=headers_dict,
                    query_params=query_params,
                    data=data,
                    raw_body=raw_body,
                    client_ip=self.client_address[0] if self.client_address else '127.0.0.1',
                )
                auth_header = headers_dict.get('Authorization') or headers_dict.get('authorization') or ''

                with parent.lock:
                    norm_path = path.rstrip('/')
                    # 1. Public Customer Registration
                    if method == 'POST' and norm_path == '/api/v1/auth/register':
                        try:
                            payload = register_customer_account(
                                data,
                                request=req_adapter,
                                conn=parent.conn,
                            )
                            self._send_json(201, payload)
                        except CustomerRegistrationValidationError as exc:
                            self._send_json(exc.status_code, exc.errors)
                        except AuthenticationBoundaryError as exc:
                            msg = str(exc)
                            code = 429 if 'throttled' in msg.lower() else 400
                            self._send_json(code, {'detail': msg})
                        return

                    # 2. Login
                    if method == 'POST' and norm_path in ('/api/v1/auth/login', '/api/v1/auth/token'):
                        ident = str(data.get('username') or data.get('email') or '').strip()
                        pw = data.get('password')
                        if not ident or not isinstance(pw, str) or not pw:
                            self._send_json(400, {'username': ['username or email and password are required.']})
                            return
                        try:
                            payload = login_with_credentials_sqlite(
                                parent.conn,
                                ident,
                                pw,
                                request=req_adapter,
                            )
                            self._send_json(200, payload)
                        except AuthenticationBoundaryError as exc:
                            msg = str(exc)
                            if 'throttled' in msg.lower():
                                self._send_json(429, {'detail': msg}, {'Retry-After': '60'})
                            else:
                                self._send_json(401, {'detail': GENERIC_INVALID_CREDENTIALS_MESSAGE})
                        return

                    # 3. Token Refresh / Rotation
                    if method == 'POST' and norm_path in (
                        '/api/v1/auth/refresh',
                        '/api/v1/auth/token/refresh',
                        '/api/v1/auth/renew',
                    ):
                        raw_refresh = str(data.get('refresh_token') or data.get('refresh') or '').strip()
                        if not raw_refresh:
                            self._send_json(400, {'refresh_token': ['refresh_token is required.']})
                            return
                        try:
                            payload = rotate_session_token_sqlite(
                                parent.conn,
                                raw_refresh,
                                request=req_adapter,
                            )
                            self._send_json(200, payload)
                        except AuthenticationBoundaryError as exc:
                            msg = str(exc)
                            if 'throttled' in msg.lower():
                                self._send_json(429, {'detail': msg}, {'Retry-After': '60'})
                            else:
                                self._send_json(401, {'detail': GENERIC_INVALID_REFRESH_TOKEN_MESSAGE})
                        return

                    # 4. Authenticated /api/v1/auth/me/
                    if method == 'GET' and norm_path == '/api/v1/auth/me':
                        if not auth_header.strip():
                            self._send_json(401, {'detail': 'Authentication credentials were not provided.'})
                            return
                        try:
                            me_payload = authenticate_access_token_sqlite(parent.conn, auth_header)
                            self._send_json(200, me_payload)
                        except AuthenticationBoundaryError:
                            self._send_json(401, {'detail': GENERIC_INVALID_ACCESS_TOKEN_MESSAGE})
                        return

                    # 5. Authenticated /api/v1/auth/logout/
                    if method == 'POST' and norm_path == '/api/v1/auth/logout':
                        if not auth_header.strip():
                            self._send_json(401, {'detail': 'Authentication credentials were not provided.'})
                            return
                        all_devices = bool(data.get('all_devices', False))
                        try:
                            revoke_token_session_sqlite(
                                parent.conn,
                                auth_header,
                                all_devices=all_devices,
                            )
                            self._send_json(200, {'detail': 'Logged out successfully.'})
                        except AuthenticationBoundaryError:
                            self._send_json(401, {'detail': GENERIC_INVALID_ACCESS_TOKEN_MESSAGE})
                        return

                    # 6. Catalog endpoints (/api/v1/products/ and /api/v1/categories/)
                    if norm_path in ('/api/v1/products', '/api/v1/categories'):
                        actor = None
                        if auth_header.strip():
                            try:
                                me_payload = authenticate_access_token_sqlite(parent.conn, auth_header)
                                actor = ServiceActor(
                                    actor_id=me_payload['actor_id'],
                                    role=me_payload['role'],
                                    customer_id=me_payload['customer_id'],
                                    store_id=me_payload.get('store_id'),
                                    authorized_branch_ids=(
                                        frozenset(me_payload['authorized_branch_ids'])
                                        if me_payload['authorized_branch_ids'] is not None
                                        else None
                                    ),
                                )
                            except AuthenticationBoundaryError:
                                self._send_json(401, {'detail': GENERIC_INVALID_ACCESS_TOKEN_MESSAGE})
                                return

                        if method == 'GET':
                            try:
                                if norm_path == '/api/v1/categories':
                                    items = list_public_or_branch_categories_sqlite(
                                        parent.conn,
                                        actor=actor,
                                        query_params=query_params,
                                        headers=headers_dict,
                                    )
                                else:
                                    items = list_public_or_branch_products_sqlite(
                                        parent.conn,
                                        actor=actor,
                                        query_params=query_params,
                                        headers=headers_dict,
                                    )
                                self._send_json(200, items)
                            except AuthenticationBoundaryError as exc:
                                self._send_json(401, {'detail': str(exc)})
                            except PermissionError as exc:
                                self._send_json(403, {'detail': str(exc)})
                            return
                        else:
                            if actor is None:
                                self._send_json(401, {'detail': 'Authentication credentials were not provided.'})
                            else:
                                self._send_json(403, {'detail': 'Only authorized store staff may modify catalog.'})
                            return

                    # 7. Orders endpoints (/api/v1/orders/ and /api/v1/orders/checkout/)
                    if norm_path in ('/api/v1/orders', '/api/v1/orders/checkout'):
                        if not auth_header.strip():
                            self._send_json(401, {'detail': 'Authentication credentials were not provided.'})
                            return
                        try:
                            me_payload = authenticate_access_token_sqlite(parent.conn, auth_header)
                            actor = ServiceActor(
                                actor_id=me_payload['actor_id'],
                                role=me_payload['role'],
                                customer_id=me_payload['customer_id'],
                                store_id=me_payload.get('store_id'),
                                authorized_branch_ids=(
                                    frozenset(me_payload['authorized_branch_ids'])
                                    if me_payload['authorized_branch_ids'] is not None
                                    else None
                                ),
                            )
                        except AuthenticationBoundaryError:
                            self._send_json(401, {'detail': GENERIC_INVALID_ACCESS_TOKEN_MESSAGE})
                            return

                        if method == 'GET' and norm_path == '/api/v1/orders':
                            try:
                                enforce_no_client_identity_spoofing(req_adapter, actor)
                                rows = list_orders_for_actor_sqlite(
                                    parent.conn,
                                    actor=actor,
                                    branch_id=(
                                        int(query_params['branch_id'])
                                        if 'branch_id' in query_params and str(query_params['branch_id']).isdigit()
                                        else None
                                    ),
                                )
                                self._send_json(200, rows)
                            except AuthenticationBoundaryError as exc:
                                self._send_json(403, {'detail': str(exc)})
                            return

                        if method == 'POST':
                            try:
                                enforce_no_client_identity_spoofing(req_adapter, actor)
                                if not isinstance(data, dict):
                                    self._send_json(400, {'detail': 'Checkout payload must be a JSON object.'})
                                    return

                                forbidden_top = sorted(set(data.keys()) & FORBIDDEN_ORDER_CHECKOUT_FIELDS)
                                if forbidden_top:
                                    self._send_json(
                                        400,
                                        {
                                            'detail': (
                                                f"Forbidden client fields in checkout payload: {', '.join(forbidden_top)}."
                                            )
                                        },
                                    )
                                    return

                                raw_items = data.get('items')
                                if not isinstance(raw_items, list) or len(raw_items) == 0:
                                    self._send_json(400, {'detail': 'Checkout requires at least one order item.'})
                                    return
                                for idx, raw_item in enumerate(raw_items):
                                    if isinstance(raw_item, dict):
                                        forbidden_item = sorted(set(raw_item.keys()) & FORBIDDEN_ITEM_FIELDS)
                                        if forbidden_item:
                                            self._send_json(
                                                400,
                                                {
                                                    'detail': (
                                                        f"Item #{idx + 1} contains forbidden client fields: "
                                                        f"{', '.join(forbidden_item)}."
                                                    )
                                                },
                                            )
                                            return

                                header_idem = None
                                for hk, hv in headers_dict.items():
                                    if hk.lower() in ('idempotency-key', 'x-idempotency-key') and str(hv).strip():
                                        header_idem = str(hv).strip()
                                        break
                                body_idem_raw = data.get('idempotency_key')
                                body_idem = str(body_idem_raw).strip() if body_idem_raw is not None and str(body_idem_raw).strip() else None
                                if header_idem and body_idem and header_idem != body_idem:
                                    raise IdempotencyConflictError(
                                        'Idempotency-Key header and body idempotency_key do not match.'
                                    )
                                idem_key = header_idem or body_idem
                                if not idem_key:
                                    self._send_json(
                                        400,
                                        {'detail': 'idempotency_key is required via Idempotency-Key header or request body.'},
                                    )
                                    return

                                result = checkout_order(
                                    actor=actor,
                                    branch_id=data.get('branch_id'),  # type: ignore[arg-type]
                                    items=raw_items,
                                    idempotency_key=idem_key,
                                    warehouse_id=data.get('warehouse_id'),
                                    customer_id=actor.customer_id,
                                    client_total_halalas=data.get('total_halalas'),
                                    expected_total_halalas=data.get('expected_total_halalas'),
                                    reject_client_pricing=bool(data.get('reject_client_pricing', False)),
                                    conn=parent.conn,
                                )

                                cur_order = parent.conn.execute(
                                    "SELECT timestamp, updated_at FROM orders_order WHERE id = ?",
                                    (result.order_id,),
                                ).fetchone()
                                ts_val = str(cur_order[0]) if cur_order else ''
                                up_val = str(cur_order[1]) if cur_order else ''
                                cur_items = parent.conn.execute(
                                    """
                                    SELECT id, product_id, product_name_snapshot, unit_snapshot,
                                           quantity_milli, unit_price_halalas, line_total_halalas, created_at
                                    FROM orders_orderitem
                                    WHERE order_id = ?
                                    ORDER BY id ASC
                                    """,
                                    (result.order_id,),
                                ).fetchall()
                                items_payload = [
                                    {
                                        'id': int(r[0]),
                                        'product': int(r[1]),
                                        'product_id': int(r[1]),
                                        'product_name_snapshot': str(r[2]),
                                        'unit_snapshot': str(r[3]),
                                        'quantity_milli': int(r[4]),
                                        'unit_price_halalas': int(r[5]),
                                        'line_total_halalas': int(r[6]),
                                        'created_at': str(r[7]),
                                    }
                                    for r in cur_items
                                ]
                                cur_hist = parent.conn.execute(
                                    """
                                    SELECT id, from_status, to_status, changed_by_actor_id, reason, changed_at
                                    FROM orders_orderstatushistory
                                    WHERE order_id = ?
                                    ORDER BY id ASC
                                    """,
                                    (result.order_id,),
                                ).fetchall()
                                hist_payload = [
                                    {
                                        'id': int(h[0]),
                                        'from_status': h[1],
                                        'to_status': str(h[2]),
                                        'changed_by_actor_id': str(h[3]),
                                        'reason': str(h[4]),
                                        'changed_at': str(h[5]),
                                    }
                                    for h in cur_hist
                                ]
                                order_response = {
                                    'id': result.order_id,
                                    'branch': result.branch_id,
                                    'branch_id': result.branch_id,
                                    'fulfillment_warehouse': result.warehouse_id,
                                    'fulfillment_warehouse_id': result.warehouse_id,
                                    'customer_id': result.customer_id,
                                    'status': result.status,
                                    'total_halalas': result.total_halalas,
                                    'idempotency_key': result.idempotency_key,
                                    'cancellation_restored': False,
                                    'timestamp': ts_val,
                                    'updated_at': up_val,
                                    'items': items_payload,
                                    'status_history': hist_payload,
                                    'replayed': result.replayed,
                                }
                                status_code = 200 if result.replayed else 201
                                self._send_json(status_code, order_response)
                            except AuthenticationBoundaryError as exc:
                                self._send_json(403, {'detail': str(exc)})
                            except CheckoutResourceNotFoundError as exc:
                                self._send_json(404, {'detail': str(exc)})
                            except (InsufficientStockError, IdempotencyConflictError) as exc:
                                self._send_json(409, {'detail': str(exc)})
                            except (SpoofedFinancialDataError, CheckoutValidationError) as exc:
                                self._send_json(400, {'detail': str(exc)})
                            except Exception:
                                self._send_json(
                                    500,
                                    {
                                        'detail': (
                                            'An internal error occurred while processing the checkout transaction.'
                                        )
                                    },
                                )
                            return

                    self._send_json(404, {'detail': 'Not found.'})

        self.httpd = ThreadingHTTPServer((host, port), Handler)
        self.port = int(self.httpd.server_address[1])
        self._thread: threading.Thread | None = None

    def start_in_background(self) -> int:
        self._thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self._thread.start()
        return self.port

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.conn.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=0)
    args = parser.parse_args()

    server = FreshContractHttpServer(host=args.host, port=args.port)
    port = server.start_in_background()
    print(f"READY {port}", flush=True)
    try:
        for line in sys.stdin:
            if line.strip().upper() == 'STOP':
                break
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


if __name__ == '__main__':
    main()

