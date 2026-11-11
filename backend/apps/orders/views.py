from django.contrib.auth import authenticate, get_user_model
from django.shortcuts import get_object_or_404
from rest_framework import status, viewsets
from rest_framework.decorators import action
from rest_framework.exceptions import PermissionDenied
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .auth import (
    GENERIC_INVALID_CREDENTIALS_MESSAGE,
    GENERIC_INVALID_REFRESH_TOKEN_MESSAGE,
    CustomerRegistrationValidationError,
    IssuedTokenPair,
    LoginRateThrottle,
    TokenRefreshRateThrottle,
    clear_failed_login_attempts,
    enforce_auth_rate_limit,
    issue_session_token_pair,
    record_failed_login_attempt,
    register_customer_account,
    resolve_service_actor_from_user,
    revoke_all_user_tokens,
    revoke_token_session,
    rotate_session_token,
)
from .models import AuthSessionToken, Order
from .permissions import (
    IsAuthenticatedActiveProfile,
    OrderObjectPermission,
    get_or_resolve_request_actor,
)
from .serializers import (
    AuthLoginRequestSerializer,
    AuthLogoutRequestSerializer,
    AuthRegisterRequestSerializer,
    AuthTokenRefreshRequestSerializer,
    CancelOrderRequestSerializer,
    CheckoutRequestSerializer,
    OrderSerializer,
    OrderStatusTransitionRequestSerializer,
from .services import (
    ActorRole,
    AuthenticationBoundaryError,
    BranchWarehouseMismatchError,
    CheckoutItemInput,
    CheckoutResourceNotFoundError,
    CheckoutValidationError,
    IdempotencyConflictError,
    InsufficientStockError,
    InvalidOrderStatusTransitionError,
    MissingStockBalanceError,
    OrderCancellationError,
    SpoofedFinancialDataError,
    cancel_order,
    checkout_order,
    transition_order_status,
)


def _extract_idempotency_key(request, serializer_data: dict) -> str:
    body_key_raw = serializer_data.get('idempotency_key')
    header_key_raw = (
        request.headers.get('Idempotency-Key')
        or request.headers.get('X-Idempotency-Key')
    )
    body_key = body_key_raw.strip() if isinstance(body_key_raw, str) else ''
    header_key = header_key_raw.strip() if isinstance(header_key_raw, str) else ''
    if body_key and header_key and body_key != header_key:
        raise IdempotencyConflictError(
            "Idempotency-Key header does not match body idempotency_key."
        )
    return body_key or header_key


def _build_auth_token_response_payload(user, actor, token_pair: IssuedTokenPair) -> dict:
    profile = user.identity_profile
    return {
        'token': token_pair.access_token,
        'access_token': token_pair.access_token,
        'refresh_token': token_pair.refresh_token,
        'token_type': 'Token',
        'expires_in': token_pair.access_expires_in,
        'expires_at': token_pair.access_expires_at.isoformat(),
        'refresh_expires_in': token_pair.refresh_expires_in,
        'refresh_expires_at': token_pair.refresh_expires_at.isoformat(),
        'actor_id': actor.actor_id,
        'username': user.username,
        'email': getattr(user, 'email', '') or '',
        'full_name': getattr(user, 'first_name', '') or user.username,
        'phone_number': getattr(user, 'last_name', '') or '',
        'role': actor.role,
        'customer_id': actor.customer_id,
        'store_id': profile.store_id,
        'authorized_branch_ids': (
            sorted(actor.authorized_branch_ids)
            if actor.authorized_branch_ids is not None
            else None
        ),
    }


class AuthRegisterView(APIView):
    """
    Public customer registration endpoint (`POST /api/v1/auth/register/`):
    - Strictly creates `CUSTOMER` accounts only; rejects any attempt to supply `role`,
      `actor_role`, `is_staff`, `is_superuser`, `branch_id`, `store_id`, or privileged roles
      (`OWNER`, `BRANCH_MANAGER`, `ADMIN`).
    - Validates `email`, `full_name`, `phone_number`, and `password` strength, and rejects
      duplicate `username`, `email`, or `phone_number`.
    - Hashes passwords with Django PBKDF2-SHA256, provisions `UserIdentityProfile(role='CUSTOMER')`,
      and issues an initial HS256 JWT access + refresh token pair (`201 Created`).
    """
    authentication_classes = []
    permission_classes = [AllowAny]
    throttle_classes = [LoginRateThrottle]

    @enforce_auth_rate_limit('login')
    def post(self, request, *args, **kwargs):
        raw_data = request.data if isinstance(request.data, dict) else {}
        serializer = AuthRegisterRequestSerializer(
            data=raw_data,
            context={'request': request},
        )
        if not serializer.is_valid():
            record_failed_login_attempt(request)
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        try:
            payload = register_customer_account(raw_data, request=request)
        except CustomerRegistrationValidationError as exc:
            record_failed_login_attempt(request)
            return Response(exc.errors, status=exc.status_code)

        clear_failed_login_attempts(request, payload.get('username'))
        return Response(payload, status=status.HTTP_201_CREATED)


class AuthLoginView(APIView):
    """
    Exchanges valid Django User credentials for an expiring signed JWT access + refresh token pair
    after verifying that the account has an active server-side `UserIdentityProfile`
    and active store/branch relationships.
    Enforces cache-based login rate limiting and repeated failed-attempt throttling, and returns
    uniform 401 responses without account-enumeration details on any credential or
    account-state failure.
    """
    authentication_classes = []
    permission_classes = [AllowAny]
    throttle_classes = [LoginRateThrottle]

    @enforce_auth_rate_limit('login')
    def post(self, request, *args, **kwargs):
        serializer = AuthLoginRequestSerializer(data=request.data)
        if not serializer.is_valid():
            record_failed_login_attempt(request)
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        identifier = serializer.validated_data['username'].strip()
        password = serializer.validated_data['password']

        UserModel = get_user_model()
        user_record = UserModel.objects.filter(username=identifier).first()
        if user_record is None and '@' in identifier:
            user_record = (
                UserModel.objects.filter(email__iexact=identifier)
                .order_by('id')
                .first()
            )
        effective_username = user_record.username if user_record is not None else identifier
        user = authenticate(request=request, username=effective_username, password=password)

        if user_record is not None and not user_record.is_active:
            revoke_all_user_tokens(
                user_record,
                reason=AuthSessionToken.RevocationReason.ACCOUNT_INACTIVE,
                request=request,
            )

        if user is None:
            record_failed_login_attempt(request, identifier)
            return Response(
                {'detail': GENERIC_INVALID_CREDENTIALS_MESSAGE},
                status=status.HTTP_401_UNAUTHORIZED,
            )

        try:
            actor = resolve_service_actor_from_user(user)
        except AuthenticationBoundaryError:
            revoke_all_user_tokens(
                user,
                reason=AuthSessionToken.RevocationReason.ACCOUNT_INACTIVE,
                request=request,
            )
            record_failed_login_attempt(request, identifier)
            return Response(
                {'detail': GENERIC_INVALID_CREDENTIALS_MESSAGE},
                status=status.HTTP_401_UNAUTHORIZED,
            )

        clear_failed_login_attempts(request, effective_username)
        token_pair = issue_session_token_pair(user)
        return Response(
            _build_auth_token_response_payload(user, actor, token_pair),
            status=status.HTTP_200_OK,
        )


class AuthTokenRefreshView(APIView):
    """
    Rotates a valid, non-expired, non-revoked refresh JWT into a new access + refresh
    JWT pair and immediately blocklists/revokes the used refresh token, its associated
    access token, and prior session data (`ROTATED`).
    If a previously rotated refresh token is replayed, blocklists and revokes the entire
    token family (`REUSE_DETECTED`), invalidates session data, and returns 401 Unauthorized.
    """
    authentication_classes = []
    permission_classes = [AllowAny]
    throttle_classes = [TokenRefreshRateThrottle]

    @enforce_auth_rate_limit('refresh')
    def post(self, request, *args, **kwargs):
        serializer = AuthTokenRefreshRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        raw_refresh_token = serializer.validated_data['refresh_token']

        try:
            token_pair = rotate_session_token(raw_refresh_token, request=request)
            user = token_pair.session_token.user
            actor = resolve_service_actor_from_user(user)
        except AuthenticationBoundaryError:
            return Response(
                {'detail': GENERIC_INVALID_REFRESH_TOKEN_MESSAGE},
                status=status.HTTP_401_UNAUTHORIZED,
            )

        return Response(
            _build_auth_token_response_payload(user, actor, token_pair),
            status=status.HTTP_200_OK,
        )


class AuthMeView(APIView):
    """
    Returns the server-resolved identity, role, customer_id, and authorized branch IDs
    for the currently authenticated user.
    """
    permission_classes = [IsAuthenticated, IsAuthenticatedActiveProfile]

    def get(self, request, *args, **kwargs):
        actor = get_or_resolve_request_actor(request)
        profile = request.user.identity_profile
        return Response(
            {
                'actor_id': actor.actor_id,
                'username': request.user.username,
                'email': getattr(request.user, 'email', '') or '',
                'full_name': getattr(request.user, 'first_name', '') or request.user.username,
                'phone_number': getattr(request.user, 'last_name', '') or '',
                'role': actor.role,
                'customer_id': actor.customer_id,
                'store_id': profile.store_id,
                'authorized_branch_ids': (
                    sorted(actor.authorized_branch_ids)
                    if actor.authorized_branch_ids is not None
                    else None
                ),
            },
            status=status.HTTP_200_OK,
        )


class AuthLogoutView(APIView):
    """
    Immediately blocklists and revokes the authenticated session token family (invalidating
    both the access token and refresh token) or all active sessions for the user when
    `all_devices=True`, and flushes associated session data.
    """
    permission_classes = [IsAuthenticated]

    def post(self, request, *args, **kwargs):
        serializer = AuthLogoutRequestSerializer(
            data=request.data if isinstance(request.data, dict) else {}
        )
        serializer.is_valid(raise_exception=True)
        revoke_all = serializer.validated_data.get('all_devices', False)

        session_token = request.auth if isinstance(request.auth, AuthSessionToken) else None
        revoke_token_session(
            user=request.user,
            session_token=session_token,
            revoke_all_user_sessions=revoke_all,
            reason=AuthSessionToken.RevocationReason.LOGOUT,
            request=request,
        )
        return Response({'detail': 'Logged out successfully.'}, status=status.HTTP_200_OK)



class OrderViewSet(viewsets.ReadOnlyModelViewSet):
    """
    Authenticated, role- and branch-scoped order viewset.
    - Unauthenticated requests are rejected with 401/403.
    - Direct arbitrary model mutation (update/partial_update/destroy) is prohibited via ReadOnlyModelViewSet.
    - Authenticated checkout (`POST /api/v1/orders/` or `POST /api/v1/orders/checkout/`),
      status progression (`POST /api/v1/orders/{id}/transition-status/`), and single-execution
      cancellation (`POST /api/v1/orders/{id}/cancel/`) delegate to `apps.orders.services`
      using the server-resolved `ServiceActor`.
    """
    queryset = Order.objects.none()
    serializer_class = OrderSerializer
    permission_classes = [IsAuthenticated, IsAuthenticatedActiveProfile, OrderObjectPermission]

    def _base_order_queryset(self):
        return (
            Order.objects.select_related('branch', 'fulfillment_warehouse')
            .prefetch_related('items', 'status_history')
            .order_by('-timestamp', '-id')
        )

    def get_queryset(self):
        actor = get_or_resolve_request_actor(self.request)
        qs = self._base_order_queryset()

        if actor.role == ActorRole.CUSTOMER.value:
            qs = qs.filter(customer_id=actor.customer_id)
        elif actor.role in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value):
            authorized_branches = actor.authorized_branch_ids or frozenset()
            branch_param = self.request.query_params.get('branch_id') or self.request.query_params.get('branch')
            if branch_param is not None:
                try:
                    requested_branch_id = int(branch_param)
                except (TypeError, ValueError) as exc:
                    raise PermissionDenied(detail="Invalid branch_id query parameter.") from exc
                if requested_branch_id not in authorized_branches:
                    raise PermissionDenied(
                        detail=f"Actor '{actor.actor_id}' is not authorized for branch {requested_branch_id}."
                    )
                qs = qs.filter(branch_id=requested_branch_id)
            else:
                qs = qs.filter(branch_id__in=authorized_branches)
        else:
            return Order.objects.none()

        status_param = self.request.query_params.get('status')
        if status_param:
            qs = qs.filter(status=status_param.strip())

        return qs

    def get_object(self):
        obj = get_object_or_404(self._base_order_queryset(), pk=self.kwargs['pk'])
        self.check_object_permissions(self.request, obj)
        return obj

    def _execute_authenticated_checkout(self, request):
        actor = get_or_resolve_request_actor(request)
        if actor.role != ActorRole.CUSTOMER.value:
            return Response(
                {
                    'detail': 'Only authenticated CUSTOMER accounts may place checkout orders.',
                    'code': 'authorization_denied',
                },
                status=status.HTTP_403_FORBIDDEN,
            )

        req_serializer = CheckoutRequestSerializer(data=request.data)
        req_serializer.is_valid(raise_exception=True)
        validated = req_serializer.validated_data

        try:
            idempotency_key = _extract_idempotency_key(request, validated)
        except IdempotencyConflictError as exc:
            return Response(
                {'detail': str(exc), 'code': 'idempotency_conflict'},
                status=status.HTTP_409_CONFLICT,
            )

        if not idempotency_key:
            return Response(
                {
                    'detail': 'idempotency_key is required (in body or Idempotency-Key header).',
                    'code': 'validation_error',
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        checkout_items = [
            CheckoutItemInput(
                product_id=item['product_id'],
                quantity_milli=item['quantity_milli'],
                client_unit_price_halalas=item.get('unit_price_halalas', item.get('price_halalas')),
                client_line_total_halalas=item.get('line_total_halalas'),
                expected_unit_price_halalas=item.get('expected_unit_price_halalas'),
            )
            for item in validated['items']
        ]

        try:
            result = checkout_order(
                actor=actor,
                customer_id=actor.customer_id,
                branch_id=validated['branch_id'],
                fulfillment_warehouse_id=validated['fulfillment_warehouse_id'],
                items=checkout_items,
                idempotency_key=idempotency_key,
                client_total_halalas=validated.get('total_halalas'),
                expected_total_halalas=validated.get('expected_total_halalas'),
            )
        except AuthenticationBoundaryError as exc:
            return Response(
                {'detail': str(exc), 'code': 'authorization_denied'},
                status=status.HTTP_403_FORBIDDEN,
            )
        except (IdempotencyConflictError, InsufficientStockError) as exc:
            code = (
                'insufficient_stock'
                if isinstance(exc, InsufficientStockError)
                else 'idempotency_conflict'
            )
            return Response(
                {'detail': str(exc), 'code': code},
                status=status.HTTP_409_CONFLICT,
            )
        except CheckoutResourceNotFoundError as exc:
            return Response(
                {'detail': str(exc), 'code': 'not_found'},
                status=status.HTTP_404_NOT_FOUND,
            )
        except (
            SpoofedFinancialDataError,
            MissingStockBalanceError,
            BranchWarehouseMismatchError,
            CheckoutValidationError,
        ) as exc:
            return Response(
                {'detail': str(exc), 'code': 'validation_error'},
                status=status.HTTP_400_BAD_REQUEST,
            )
        except Exception:
            return Response(
                {
                    'detail': 'An unexpected internal server error occurred while processing checkout.',
                    'code': 'internal_server_error',
                },
                status=status.HTTP_500_INTERNAL_SERVER_ERROR,
            )

        order = self._base_order_queryset().get(pk=result.order_id)
        payload = OrderSerializer(order).data
        payload['replayed'] = result.replayed
        http_status = status.HTTP_200_OK if result.replayed else status.HTTP_201_CREATED
        return Response(payload, status=http_status)

    def create(self, request, *args, **kwargs):
        return self._execute_authenticated_checkout(request)

    @action(detail=False, methods=['post'], url_path='checkout')
    def checkout(self, request, *args, **kwargs):
        return self._execute_authenticated_checkout(request)

    def _execute_status_transition(self, request, pk=None):
        actor = get_or_resolve_request_actor(request)
        if actor.role not in (ActorRole.BRANCH_MANAGER.value, ActorRole.OWNER.value):
            return Response(
                {'detail': 'Only authorized BRANCH_MANAGER or OWNER accounts may transition order status.'},
                status=status.HTTP_403_FORBIDDEN,
            )

        order = self.get_object()
        req_serializer = OrderStatusTransitionRequestSerializer(data=request.data)
        req_serializer.is_valid(raise_exception=True)

        try:
            transition_res = transition_order_status(
                actor=actor,
                order_id=order.id,
                to_status=req_serializer.validated_data['to_status'],
                reason=req_serializer.validated_data.get('reason', ''),
            )
        except AuthenticationBoundaryError as exc:
            return Response({'detail': str(exc)}, status=status.HTTP_403_FORBIDDEN)
        except InvalidOrderStatusTransitionError as exc:
            return Response({'detail': str(exc)}, status=status.HTTP_400_BAD_REQUEST)

        order.refresh_from_db()
        refreshed = self._base_order_queryset().get(pk=order.id)
        payload = OrderSerializer(refreshed).data
        payload['transition'] = {
            'from_status': transition_res.from_status,
            'to_status': transition_res.to_status,
            'changed_by_actor_id': transition_res.changed_by_actor_id,
            'reason': transition_res.reason,
        }
        return Response(payload, status=status.HTTP_200_OK)

    @action(detail=True, methods=['post'], url_path='transition-status')
    def transition_status(self, request, pk=None):
        return self._execute_status_transition(request, pk=pk)

    @action(detail=True, methods=['post'], url_path='status')
    def update_status(self, request, pk=None):
        return self._execute_status_transition(request, pk=pk)

    @action(detail=True, methods=['post'], url_path='cancel')
    def cancel(self, request, pk=None):
        actor = get_or_resolve_request_actor(request)
        order = self.get_object()

        req_serializer = CancelOrderRequestSerializer(data=request.data)
        req_serializer.is_valid(raise_exception=True)
        validated = req_serializer.validated_data

        idempotency_key = _extract_idempotency_key(request, validated)
        if not idempotency_key:
            return Response(
                {'detail': 'idempotency_key is required (in body or Idempotency-Key header).'},
                status=status.HTTP_400_BAD_REQUEST,
            )

        try:
            cancel_res = cancel_order(
                actor=actor,
                order_id=order.id,
                idempotency_key=idempotency_key,
                reason=validated.get('reason', 'Order cancelled and stock restored'),
            )
        except AuthenticationBoundaryError as exc:
            return Response({'detail': str(exc)}, status=status.HTTP_403_FORBIDDEN)
        except IdempotencyConflictError as exc:
            return Response({'detail': str(exc)}, status=status.HTTP_409_CONFLICT)
        except OrderCancellationError as exc:
            return Response({'detail': str(exc)}, status=status.HTTP_409_CONFLICT)
        except (InvalidOrderStatusTransitionError, BranchWarehouseMismatchError, MissingStockBalanceError) as exc:
            return Response({'detail': str(exc)}, status=status.HTTP_400_BAD_REQUEST)

        refreshed = self._base_order_queryset().get(pk=order.id)
        payload = OrderSerializer(refreshed).data
        payload['replayed'] = cancel_res.replayed
        payload['from_status'] = cancel_res.from_status
        payload['restored_items'] = [
            {'product_id': prod_id, 'quantity_milli': qty}
            for prod_id, qty in cancel_res.restored_items
        ]
        return Response(payload, status=status.HTTP_200_OK)
