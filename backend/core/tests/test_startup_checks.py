"""
Unit tests for core system checks (SECRET_KEY and ALLOWED_HOSTS validation when DEBUG=False).
"""

from __future__ import annotations

from django.test import SimpleTestCase, override_settings
from django.core.checks import Error
from core.checks import validate_production_security_settings


class StartupSecurityChecksTestCase(SimpleTestCase):

    @override_settings(DEBUG=False, SECRET_KEY='django-insecure-dummy-key', ALLOWED_HOSTS=['api.fresh.sa'])
    def test_insecure_secret_key_raises_error(self):
        errors = validate_production_security_settings(None)
        self.assertTrue(any(e.id == 'fresh.E002' for e in errors))

    @override_settings(DEBUG=False, SECRET_KEY='a' * 60, ALLOWED_HOSTS=[])
    def test_empty_allowed_hosts_raises_error(self):
        errors = validate_production_security_settings(None)
        self.assertTrue(any(e.id == 'fresh.E006' for e in errors))

    @override_settings(DEBUG=True, SECRET_KEY='django-insecure-dummy-key', ALLOWED_HOSTS=[])
    def test_debug_true_bypasses_production_checks(self):
        errors = validate_production_security_settings(None)
        self.assertEqual(errors, [])

    @override_settings(
        DEBUG=False,
        SECRET_KEY='valid-production-secret-key-with-high-entropy-and-length-exceeding-fifty-chars-0123456789!',
        ALLOWED_HOSTS=['api.fresh.sa']
    )
    def test_valid_production_settings_pass(self):
        errors = validate_production_security_settings(None)
        self.assertEqual(errors, [])
