import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = os.environ.get('SECRET_KEY', 'django-insecure-dummy-key')
DEBUG = os.environ.get('DEBUG', 'True') == 'True'
ALLOWED_HOSTS = os.environ.get('ALLOWED_HOSTS', '127.0.0.1,localhost').split(',')

INSTALLED_APPS = [
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    'rest_framework',
    'rest_framework.authtoken',
    'apps.products',
    'apps.orders',
]

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'apps.orders.middleware.FailedLoginAttemptMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
]

ROOT_URLCONF = 'fresh_api.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.debug',
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
            ],
        },
    },
]

WSGI_APPLICATION = 'fresh_api.wsgi.application'

DATABASES = {
    'default': {
        'ENGINE': os.environ.get('DB_ENGINE', 'django.db.backends.postgresql'),
        'NAME': os.environ.get('DB_NAME', 'fresh_db'),
        'USER': os.environ.get('DB_USER', 'fresh_user'),
        'PASSWORD': os.environ.get('DB_PASSWORD', ''),
        'HOST': os.environ.get('DB_HOST', 'localhost'),
        'PORT': os.environ.get('DB_PORT', '5432'),
    }
}

CACHES = {
    'default': {
        'BACKEND': os.environ.get(
            'CACHE_BACKEND',
            'django.core.cache.backends.locmem.LocMemCache',
        ),
        'LOCATION': os.environ.get('CACHE_LOCATION', 'fresh-auth-security-cache'),
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {
        'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator',
    },
]

REST_FRAMEWORK = {
    'DEFAULT_AUTHENTICATION_CLASSES': [
        'apps.orders.auth.ExpiringTokenAuthentication',
        'rest_framework.authentication.SessionAuthentication',
    ],
    'DEFAULT_PERMISSION_CLASSES': [
        'rest_framework.permissions.IsAuthenticated',
    ],
}

# Authentication JWT, Token Lifecycle & Rate Limiting Configuration (override via environment)
AUTH_JWT_ALGORITHM = os.environ.get('AUTH_JWT_ALGORITHM', 'HS256')
AUTH_JWT_ISSUER = os.environ.get('AUTH_JWT_ISSUER', 'fresh-backend')
AUTH_ACCESS_TOKEN_TTL_SECONDS = int(os.environ.get('AUTH_ACCESS_TOKEN_TTL_SECONDS', '900'))
AUTH_REFRESH_TOKEN_TTL_SECONDS = int(os.environ.get('AUTH_REFRESH_TOKEN_TTL_SECONDS', '604800'))
AUTH_MAX_FAILED_LOGIN_ATTEMPTS = int(os.environ.get('AUTH_MAX_FAILED_LOGIN_ATTEMPTS', '5'))
AUTH_FAILED_LOGIN_WINDOW_SECONDS = int(os.environ.get('AUTH_FAILED_LOGIN_WINDOW_SECONDS', '300'))
AUTH_LOGIN_RATE_LIMIT_REQUESTS = int(os.environ.get('AUTH_LOGIN_RATE_LIMIT_REQUESTS', '15'))
AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS = int(os.environ.get('AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS', '60'))
AUTH_REFRESH_RATE_LIMIT_REQUESTS = int(os.environ.get('AUTH_REFRESH_RATE_LIMIT_REQUESTS', '10'))
AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS = int(os.environ.get('AUTH_REFRESH_RATE_LIMIT_WINDOW_SECONDS', '60'))

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

LANGUAGE_CODE = 'ar-sa'
TIME_ZONE = 'UTC'
USE_I18N = True
USE_TZ = True



