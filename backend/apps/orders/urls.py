from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    AuthLoginView,
    AuthLogoutView,
    AuthMeView,
    AuthTokenRefreshView,
    OrderViewSet,
)

router = DefaultRouter()
router.register(r'orders', OrderViewSet, basename='order')

urlpatterns = [
    path('auth/login/', AuthLoginView.as_view(), name='auth-login'),
    path('auth/token/', AuthLoginView.as_view(), name='auth-token'),
    path('auth/refresh/', AuthTokenRefreshView.as_view(), name='auth-refresh'),
    path('auth/token/refresh/', AuthTokenRefreshView.as_view(), name='auth-token-refresh'),
    path('auth/renew/', AuthTokenRefreshView.as_view(), name='auth-renew'),
    path('auth/me/', AuthMeView.as_view(), name='auth-me'),
    path('auth/logout/', AuthLogoutView.as_view(), name='auth-logout'),
    path('', include(router.urls)),
]

