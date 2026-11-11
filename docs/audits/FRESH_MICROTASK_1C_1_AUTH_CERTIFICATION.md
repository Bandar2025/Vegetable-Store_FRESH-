# FRESH — Microtask 1C.1: Production Authentication Verification & Certification Gate

**التاريخ:** `2026-11-11`  
**المشروع:** `FRESH — Saudi Grocery / Fresh Produce Shopping Application`  
**النطاق:** التحقق الهندسي المستقل وشهادة الاعتماد لتنفيذ **Microtask 1C** (تسجيل العملاء وربط مصادقة Android بنظام JWT في Django) قبل الانتقال إلى **Microtask 1D**.  
**سياسة عدم تسريب الأسرار:** تم إخفاء وحجب جميع قيم كلمات المرور والرموز (`JWT`) والمفاتيح السرية (`[REDACTED]`) في هذا التقرير وفي جميع السجلات والاختبارات.

---

## 1. الملفات التي فُحصت (Inspected Files Inventory)

تم فحص الملفات الفعلية التالية سطرًا بسطر للتحقق من التنفيذ والعقود والأمان:

### أ. الخادم (Backend — Django / Python)
1. `backend/fresh_api/settings.py` (702 سطرًا) — إعدادات الأمان في الإنتاج (`DEBUG`, `SECRET_KEY`, `ALLOWED_HOSTS`, `DATABASES`, `CACHES`, `AUTH_JWT_*`, `AUTH_MAX_FAILED_LOGIN_ATTEMPTS`).
2. `backend/core/checks.py` (110 أسطر) — فحوصات نظام Django عند الإقلاع (`fresh.E001` إلى `fresh.E007`).
3. `backend/core/tests/test_startup_checks.py` (37 سطرًا) — اختبارات فحوصات الإقلاع.
4. `backend/middleware/auth_rate_limit.py` (485 سطرًا) — وسيط تحديد معدل طلبات المصادقة والتسجيل (`LOGIN_RATE_LIMITED_PATHS`, `REFRESH_RATE_LIMITED_PATHS`, `AuthRateLimitMiddleware`).
5. `backend/apps/orders/models.py` — نماذج `UserIdentityProfile`, `BranchRoleAssignment`, `AuthSessionToken`, `AuthThrottleEvent`.
6. `backend/apps/orders/auth.py` (3,024 سطرًا) — منطق التسجيل العام (`register_customer_account`, `validate_and_normalize_customer_registration_payload`)، وإصدار وتدوير وإبطال رموز `HS256 JWT` في مساري Django ORM وSQLite Reference Engine.
7. `backend/apps/orders/serializers.py` (186 سطرًا) — المحولات (`AuthRegisterRequestSerializer`, `AuthLoginRequestSerializer`, `AuthTokenRefreshRequestSerializer`, `AuthLogoutRequestSerializer`).
8. `backend/apps/orders/views.py` (497 سطرًا) — واجهات API (`AuthRegisterView`, `AuthLoginView`, `AuthTokenRefreshView`, `AuthMeView`, `AuthLogoutView`).
9. `backend/apps/orders/urls.py` (28 سطرًا) — مسارات `/api/v1/auth/*`.
10. `backend/apps/orders/test_http_server.py` (503 أسطر) — خادم العقود الفعلي (`FreshContractHttpServer`) لاختبارات التكامل عبر HTTP.
11. `backend/apps/orders/tests.py` (3,499 سطرًا) و`backend/apps/products/tests.py` — حزم اختبارات الخادم.
12. `backend/requirements.txt` — متطلبات حزم Python (`Django==5.1.0`, `djangorestframework==3.15.2`, `psycopg2-binary==2.9.9`, `python-dotenv==1.0.1`).

### ب. تطبيق Android (Kotlin / Jetpack Compose)
13. `app/src/main/java/com/example/data/auth/SecureTokenStorage.kt` (397 سطرًا) — التشفير `AES/GCM/NoPadding` (256-bit) عبر `AndroidKeyStoreAesGcmCipher` و`JvmAesGcmCipher`.
14. `app/src/main/java/com/example/data/api/model/ApiModels.kt` (312 سطرًا) — نماذج DTOs الخاصة بالمصادقة والكتالوج.
15. `app/src/main/java/com/example/data/api/FreshApiService.kt` (418 سطرًا) — تعريفات Retrofit ومعترض `FreshAuthInterceptor` وآلية `refreshTokensSingleFlight`.
16. `app/src/main/java/com/example/data/auth/AuthModels.kt` — نموذج `AuthenticatedUser` وحقول الهوية الخادمية.
17. `app/src/main/java/com/example/data/auth/LocalAuthRepository.kt` — مستودع المصادقة الموحد وربطه بـ `FreshApiService` و`SecureTokenStorage` وبوابة `BuildConfig.DEBUG`.
18. `app/src/main/java/com/example/ui/viewmodel/AuthViewModel.kt` و`app/src/main/java/com/example/ui/screens/AuthScreens.kt` — تمرير `isDebugBuild` وإخفاء الحسابات التجريبية في نسخ الإطلاق.
19. `app/src/main/java/com/example/di/Injection.kt` — حقن الاعتماديات الموحد.
20. `app/src/test/java/com/example/MicroTask1ADataIntegrityAndDiTest.kt` و`MicroTask1BApiContractAlignmentTest.kt` و`MicroTask1CAuthAndJwtIntegrationTest.kt` (752 سطرًا).

---

## 2. المرحلة الأولى: تدقيق Git والملفات المعدلة فعليًا ومطابقتها مع تقرير 1C (Git & Workspace Change Audit)

### أ. حالة مستودع Git في حاوية البناء
- عند تنفيذ `git status --short` و`git diff --stat` داخل مساحة العمل `/app/applet`:
  ```text
  fatal: not a git repository (or any of the parent directories): .git
  ```
- **السبب الهندسي:** بيئة البناء السحابية تنسخ شجرة ملفات المشروع مباشرة إلى `/app/applet` دون مجلد `.git`. لذلك أُجري التدقيق المباشر على الملفات الفعلية، وتواريخ تعديلها، ومحتواها البرمجي سطرًا بسطر مقارنةً بما ورد في تقرير ملخص Microtask 1C.

### ب. الملفات التي أنشئت أو تغيرت فعليًا ضمن نطاق Microtask 1C و1C.1
| الملف الفعلي على القرص | حالة الملف | التطابق مع تقرير 1C وملاحظات التدقيق |
|---|---|---|
| `backend/middleware/auth_rate_limit.py` | معدل | يضيف `/api/v1/auth/register/` و`/api/v1/auth/register` إلى `LOGIN_RATE_LIMITED_PATHS` (السطر 107–114). *(ملاحظة تدقيق: التقرير السردي لـ 1C أشار للاسم العام `DEFAULT_AUTH_RATE_LIMIT_PATHS` بينما الاسم الفعلي في الكود هو `LOGIN_RATE_LIMITED_PATHS`).* |
| `backend/apps/orders/auth.py` | معدل | يضيف `register_customer_account`، `validate_and_normalize_customer_registration_payload`، `normalize_saudi_phone`، `hash_password_pbkdf2_sha256`، ودوال SQLite للمصادقة وتدوير الرموز. *(تم تعزيز `register_customer_account` في 1C.1 للتحقق من `check_and_record_login_rate_limit(request)` مباشرة).* |
| `backend/apps/orders/serializers.py` | معدل | يضيف المحول الفعلي `AuthRegisterRequestSerializer` (السطر 136). *(ملاحظة تدقيق: التقرير السردي لـ 1C أشار إليه باسم `CustomerRegistrationSerializer` بينما الاسم الفعلي في الكود هو `AuthRegisterRequestSerializer`).* |
| `backend/apps/orders/views.py` | معدل | يضيف الواجهة الفعلية `AuthRegisterView` (السطر 96) ويوحّد استجابات `AuthLoginView`، `AuthTokenRefreshView`، `AuthMeView`، `AuthLogoutView`. *(ملاحظة تدقيق: التقرير السردي لـ 1C أشار إليها باسم `CustomerRegistrationAPIView` بينما الاسم الفعلي في الكود هو `AuthRegisterView`).* |
| `backend/apps/orders/urls.py` | معدل | يسجل `path('auth/register/', AuthRegisterView.as_view(), name='auth-register')` (السطر 16). |
| `backend/apps/orders/test_http_server.py` | معدل | يدعم نقاط النهاية `/api/v1/auth/register`، `/api/v1/auth/login`، `/api/v1/auth/refresh`، `/api/v1/auth/me`، `/api/v1/auth/logout` إلى جانب `/api/v1/products` و`/api/v1/categories`. |
| `backend/apps/orders/tests.py` | معدل | يضيف الفئة `CustomerRegistrationAndJwtLifecycleVerificationTests` (10 دوال اختبار من `test_01` إلى `test_10`). |
| `app/src/main/java/com/example/data/auth/SecureTokenStorage.kt` | منشأ | مطابق تمامًا لتقرير 1C (`JwtSessionTokens`, `AndroidKeyStoreAesGcmCipher`, `JvmAesGcmCipher`, `SecureTokenStorage`). |
| `app/src/main/java/com/example/data/api/model/ApiModels.kt` | معدل | مطابق تمامًا لتقرير 1C (`AuthRegisterRequestDto`, `AuthLoginRequestDto`, `AuthRefreshRequestDto`, `AuthLogoutRequestDto`, `AuthTokenResponseDto`, `AuthMeResponseDto`). |
| `app/src/main/java/com/example/data/api/FreshApiService.kt` | معدل | مطابق تمامًا لتقرير 1C (`FreshApiService`, `FreshAuthInterceptor`, `FreshRetrofitClient`). |
| `app/src/main/java/com/example/data/auth/AuthModels.kt` | معدل | مطابق تمامًا لتقرير 1C. |
| `app/src/main/java/com/example/data/auth/LocalAuthRepository.kt` | معدل | مطابق تمامًا لتقرير 1C. |
| `app/src/main/java/com/example/ui/viewmodel/AuthViewModel.kt` | معدل | مطابق تمامًا لتقرير 1C. |
| `app/src/main/java/com/example/ui/screens/AuthScreens.kt` | معدل | مطابق تمامًا لتقرير 1C (إخفاء بطاقة الحسابات التجريبية فقط عندما يكون `isDebugBuild == false` دون تغيير التصميم). |
| `app/src/main/java/com/example/di/Injection.kt` | معدل | مطابق تمامًا لتقرير 1C. |
| `app/src/test/java/com/example/MicroTask1CAuthAndJwtIntegrationTest.kt` | منشأ | مطابق تمامًا لتقرير 1C (10 اختبارات تكامل ووحدة، وتم تعزيز `test_06` في 1C.1 ليختبر أيضًا حالة `503 Service Unavailable`). |
| `docs/audits/FRESH_MICROTASK_1C_1_AUTH_CERTIFICATION.md` | منشأ | تقرير التحقق وشهادة الاعتماد الحالي الخاص بـ Microtask 1C.1. |

---

## 3. المرحلة الثانية: التحقق من بيئة تشغيل Django وPostgreSQL (Django & PostgreSQL Runtime Verification)

### أ. فحص توفر الحزم ومحركات قواعد البيانات في الحاوية
تم تشغيل أمر الفحص المباشر:
```bash
python3 -c "
for mod in ('django', 'rest_framework', 'psycopg', 'psycopg2', 'sqlite3'):
    try:
        __import__(mod)
        print(mod, 'AVAILABLE')
    except Exception as e:
        print(mod, 'MISSING:', e)
" ; which python3 pip pip3 psql pg_isready 2>&1
```
**المخرجات الفعلية:**
```text
django MISSING: No module named 'django'
rest_framework MISSING: No module named 'rest_framework'
psycopg MISSING: No module named 'psycopg'
psycopg2 MISSING: No module named 'psycopg2'
sqlite3 AVAILABLE
/usr/bin/python3
```

### ب. نتائج Django ORM وPostgreSQL مقارنةً بـ SQLite Reference Engine
التزامًا بالقواعد الصارمة للمهمة **Microtask 1C.1** (عدم استبدال اختبارات PostgreSQL أو Django ORM بنتائج SQLite):

| طبقة / بيئة الاختبار | الحالة | التفاصيل الهندسية |
|---|---|---|
| **Django ORM Runtime (`django.test.TestCase` / DRF `APIClient`)** | **NOT EXECUTED** | حزمتا `django` و`djangorestframework` غير مثبتتين في حاوية بناء Android؛ تم تخطي `22` اختبارًا معتمدًا على Django ORM بصورة صريحة (`skipped=22`). |
| **PostgreSQL Database Engine (`psycopg2` / `postgres`)** | **NOT EXECUTED** | عميل وخادم PostgreSQL وحزمة `psycopg2` غير متوفرة في حاوية بناء Android. تم التحقق من إعدادات الاتصال في `backend/fresh_api/settings.py` (`ENGINE: django.db.backends.postgresql`, `PORT: 5432`) ساكنًا فقط. |
| **SQLite Reference Engine & Python Unit Tests (`unittest` + `sqlite3`)** | **EXECUTED & PASSED (43 / 43)** | تم تنفيذ أمر `PYTHONPATH=backend python3 -m unittest -v apps.orders.tests apps.products.tests`؛ نجح `43` اختبارًا فعليًا في `2.765s` مع `0` فشل و`0` أخطاء. |

### ج. تفصيل الاختبارات الـ 22 المتخطاة (`SKIPPED / NOT EXECUTED`) وأسبابها
1. `OrdersDjangoModelAndServiceTests` (4 اختبارات) — *السبب:* `Django ORM runtime is not installed in the Android build container`.
2. `AuthenticationAndAuthorizationDRFTests` (5 اختبارات) — *السبب:* `Django ORM & DRF runtime are required for authentication/authorization API tests`.
3. `TokenLifecycleAndRateLimitingDRFTests` (9 اختبارات) — *السبب:* `Django ORM runtime is required for token lifecycle and rate limiting tests`.
4. `BackendSecurityConfigurationHardeningTests` (اختبار واحد شامل) — *السبب:* `Django runtime is required for backend security configuration hardening tests`.
5. `ProductsDjangoModelTests` (اختباران) و`ProductsGuestCatalogAndPermissionDRFTests` (اختبار واحد) — *السبب:* `Django ORM & DRF runtime are not installed in the Android build container`.

### د. نتائج اختبارات المصادقة والتسجيل المنفذة فعليًا على محرك SQLite المرجعي (`CustomerRegistrationAndJwtLifecycleVerificationTests`)
جميع الحالات الـ 11 المطلوبة تم اختبارها وتمريرها بنجاح (`OK`):
1. **Customer Registration (`test_01`)**: `PASS` — إنشاء حساب عميل جديد بدور `CUSTOMER` ومعرف `cust-user-<id>` وإصدار زوج رموز `HS256 JWT`.
2. **Phone Normalization (`test_02`)**: `PASS` — تطبيع الأرقام السعودية (`05XXXXXXXX`, `+9665XXXXXXXX`, `9665XXXXXXXX`) ورفض الأرقام غير السعودية.
3. **Duplicate Username / Email / Phone (`test_03`)**: `PASS` — رفض تكرار اسم المستخدم أو البريد الإلكتروني (غير حساس لحالة الأحرف) أو رقم الجوال، ورفض كلمات المرور الضعيفة أو غير المتطابقة.
4. **Privilege Escalation Prevention (`test_04`)**: `PASS` — رفض جميع محاولات تمرير `role`, `actor_role`, `is_staff`, `is_superuser`, `branch_id`, `store_id`, `authorized_branch_ids`, `customer_id`, `actor_id` مع ضمان عدم إنشاء أي مستخدم (`count == 0`).
5. **Password Hashing (`test_05`)**: `PASS` — تجزئة كلمة المرور بـ `pbkdf2_sha256$600000$...` وتجزئة الرموز بـ `SHA-256` في قاعدة البيانات وعدم إرجاع كلمة المرور أو تجزئتها في الاستجابة.
6. **Login by Username or Email (`test_06`)**: `PASS` — نجاح الدخول باسم المستخدم أو البريد الإلكتروني وإرجاع رسالة خطأ موحدة عند فشل بيانات الاعتماد لمنع تعداد الحسابات (`Account Enumeration`).
7. **Refresh Token Rotation (`test_07`)**: `PASS` — تدوير `refresh_token` لمرة واحدة وإبطال الزوج القديم (`ROTATED`).
8. **Refresh Token Replay Detection (`test_07`)**: `PASS` — عند إعادة إرسال `refresh_token` قديم سبق تدويره، يتم كشف الاختراق (`REUSE_DETECTED`) وإبطال جميع الرموز النشطة في نفس `token_family_id` فورًا.
9. **Logout Revocation (`test_09`)**: `PASS` — إبطال فوري للجلسة في قاعدة البيانات وقائمة الحظر ومنع استخدام `access_token` أو `refresh_token` بعد الخروج.
10. **Registration, Login & Refresh Rate Limiting (`test_10`)**: `PASS` — تفعيل الحظر المؤقت (`throttled` / `429`) عند تجاوز الحد المسموح لمحاولات الدخول الفاشلة، أو طلبات التجديد، أو طلبات التسجيل (`POST /api/v1/auth/register/`).
11. **Customer / Store / Branch Isolation (`ProductsGuestCatalogAndPermissionVerificationTests` + `AtomicBackendCheckoutAndCancellationServiceTests`)**: `PASS` — التحقق من صلاحيات الزوار، والعملاء، ومديري الفروع، ومالكي المتاجر، وعزل الفروع.

---

## 4. المرحلة الثالثة: تدقيق عقود JWT بين Android وDjango (JWT Contract Audit)

### أ. جدول مطابقة الحقول بين Django (`views.py` / `auth.py`) وAndroid (`ApiModels.kt` / `SecureTokenStorage.kt`)
| الحقل في JSON | Django (`_build_auth_token_response_payload` & `register_customer_account`) | Android (`AuthTokenResponseDto` / `AuthMeResponseDto`) | التطابق |
|---|---|---|---|
| `token` | `str` (رمز الوصول للتوافق الخلفي) | `@SerialName("token") val token: String = ""` | متطابق 100% |
| `access_token` | `str` (رمز وصول JWT موقع بـ HS256) | `@SerialName("access_token") val accessToken: String = ""` | متطابق 100% |
| `refresh_token` | `str` (رمز تجديد JWT موقع بـ HS256) | `@SerialName("refresh_token") val refreshToken: String = ""` | متطابق 100% |
| `token_type` | `"Token"` (ويقبل الخادم في الترويسة كلاً من `Bearer` و`Token`) | `@SerialName("token_type") val tokenType: String = "Token"` | متطابق 100% |
| `Authorization` Header | `ExpiringTokenAuthentication` و`authenticate_access_token_sqlite` يقبلان `Authorization: Bearer <access_token>` | `FreshAuthInterceptor` يرسل `Authorization: Bearer <access_token>` | متطابق 100% |
| `expires_in` | `int` (`900` ثانية = 15 دقيقة) | `@SerialName("expires_in") val expiresIn: Long = 900L` | متطابق 100% |
| `expires_at` | `str` (طابع زمني ISO-8601 UTC) | `@SerialName("expires_at") val expiresAt: String = ""` | متطابق 100% |
| `refresh_expires_in` | `int` (`604800` ثانية = 7 أيام) | `@SerialName("refresh_expires_in") val refreshExpiresIn: Long = 604800L` | متطابق 100% |
| `refresh_expires_at` | `str` (طابع زمني ISO-8601 UTC) | `@SerialName("refresh_expires_at") val refreshExpiresAt: String = ""` | متطابق 100% |
| `actor_id` | `str` (`"user:<id>"`) | `@SerialName("actor_id") val actorId: String = ""` | متطابق 100% |
| `customer_id` | `str \| None` (`"cust-user-<id>"` للعميل، و`null` للمالك/مدير الفرع) | `@SerialName("customer_id") val customerId: String? = null` | متطابق 100% |
| `role` | `"CUSTOMER"` \| `"OWNER"` \| `"BRANCH_MANAGER"` | `@SerialName("role") val role: String = "CUSTOMER"` | متطابق 100% |
| `store_id` | `int \| None` | `@SerialName("store_id") val storeId: Int? = null` | متطابق 100% |
| `authorized_branch_ids` | `list[int] \| None` | `@SerialName("authorized_branch_ids") val authorizedBranchIds: List<Int>? = null` | متطابق 100% |

### ب. نتائج اختبار الحالات الثماني لدورة حياة JWT
| # | الحالة المختبرة | الاختبار الفعلي المنفذ | النتيجة |
|---|---|---|---|
| 1 | رمز وصول صالح (`Valid Access Token`) | `MicroTask1CAuthAndJwtIntegrationTest.test_10` + `test_03` | **PASS** (`200 OK` من `/api/v1/auth/me/`) |
| 2 | رمز وصول منتهي (`Expired Access Token`) | `MicroTask1CAuthAndJwtIntegrationTest.test_04` | **PASS** (يُجدد تلقائيًا عبر `/api/v1/auth/refresh/` وينجح الطلب) |
| 3 | رمز ملغى (`Revoked Access/Refresh Token`) | `MicroTask1CAuthAndJwtIntegrationTest.test_07` + `tests.py:test_09` | **PASS** (يرفضه الخادم بـ `401` ويمسحه العميل) |
| 4 | رمز Refresh صالح (`Valid Refresh Token`) | `MicroTask1CAuthAndJwtIntegrationTest.test_04` + `test_10` | **PASS** (يُصدر زوج رموز جديدًا ويحفظه مشفرًا) |
| 5 | رمز Refresh قديم سبق تدويره (`Replayed Rotated Refresh Token`) | `MicroTask1CAuthAndJwtIntegrationTest.test_10` + `tests.py:test_07` | **PASS** (`401 Unauthorized` + إبطال عائلة الرموز `REUSE_DETECTED`) |
| 6 | طلبات متزامنة متعددة تتلقى `401` (`Concurrent 401 Single-Flight Refresh`) | `MicroTask1CAuthAndJwtIntegrationTest.test_05` | **PASS** (6 طلبات متزامنة تنجح جميعها مع استدعاء تجديد واحد فقط `totalRefreshCallsExecuted == 1`) |
| 7 | انقطاع الشبكة أثناء التجديد (`Network IOException During Refresh`) | `MicroTask1CAuthAndJwtIntegrationTest.test_06` (Part A) | **PASS** (يحتفظ بالرموز المشفرة والجلسة المحلية دون طرد المستخدم) |
| 8 | فشل الخادم المؤقت `5xx` أثناء التجديد (`503 Server Error During Refresh`) | `MicroTask1CAuthAndJwtIntegrationTest.test_06` (Part B) | **PASS** (يحتفظ بالرموز المشفرة والجلسة المحلية ولا يمسحها إلا عند `401/403` صريح) |

---

## 5. المرحلة الرابعة: التدقيق الأمني (Security Verification)

1. **منع إرسال `Authorization` إلى نطاق خارجي:**
   - محقق في `FreshAuthInterceptor.isTrustedBackendRequest` (`FreshApiService.kt` السطر 243–254): يقارن `requestHost` و`requestPort` بالمضيف الموثوق، وتم إثباته آليًا في `test_03` (`https://evil-analytics.example.com/api/v1/auth/me/` -> `Authorization == null`).
2. **عدم إرسال `Bearer` إلى `login` / `register` / `refresh`:**
   - محقق في `FreshAuthInterceptor.isUnauthenticatedAuthEndpoint` (`FreshApiService.kt` السطر 256–269)، وتم إثباته آليًا في `test_03`.
3. **عدم تخزين JWT كنص صريح:**
   - محقق في `SecureTokenStorage.kt`: يتم تشفير الرموز بواسطة `AES/GCM/NoPadding` بمفتاح 256-bit ووسم مصادقة 128-bit ببادئة `ENC_AES256_GCM_V1:`. أي نص صريح أو Base64 خام أو بيانات متلاعب بها تُرفض وتُمسح فورًا (`test_01` و`test_08`).
   - **ملاحظة أمان صريحة حول Android Keystore:** في اختبارات JVM المحلية، لا يتوفر موفر الأجهزة `AndroidKeyStore` الخاص بنظام Android، لذا تستخدم الاختبارات المحلية `JvmAesGcmCipher` (الذي ينفذ نفس خوارزمية `AES/GCM/NoPadding` بمفتاح 256-bit)، بينما يستخدم التطبيق على أجهزة Android الفعلية `AndroidKeyStoreAesGcmCipher`. لم يتم الادعاء بأن Keystore العتادي تم اختباره على جهاز حقيقي داخل حاوية JVM.
4. **عدم ظهور كلمات المرور والرموز في السجلات أو `toString()`:**
   - لا يوجد أي استخدام لـ `HttpLoggingInterceptor` أو `Log.d` للرموز، كما تعيد دوال `toString()` في `JwtSessionTokens`, `AuthRegisterRequestDto`, `AuthLoginRequestDto`, `AuthRefreshRequestDto`, و`AuthTokenResponseDto` القيمة `[REDACTED]` بدل الرموز وكلمات المرور (`test_01`).
5. **منع إنشاء مستخدم بصلاحية `OWNER` أو `BRANCH_MANAGER` عبر التسجيل العام، ومنع تعيين `store_id` أو `branch_id` أو `customer_id`:**
   - محقق في `validate_and_normalize_customer_registration_payload` (`backend/apps/orders/auth.py` السطر 1877–2025): أي حقل في الجسم أو المعاملات أو الترويسات ضمن `FORBIDDEN_REGISTRATION_FIELDS` يُرفض فورًا بـ `400 Bad Request` (`test_04` و`test_10`).
6. **سلامة إبطال الجلسة عند `Logout`:**
   - محقق في `revoke_token_session` و`revoke_token_session_sqlite` (`auth.py` السطر 774 و2953): يبطل السجل في `orders_authsessiontoken` (`revocation_reason = 'LOGOUT'`) ويضيف التجزئة وعائلة الرموز إلى قائمة الحظر (`Blocklist`)، ويمسح التخزين المشفر على جهاز Android (`test_07`).
7. **سلامة إعدادات `SECRET_KEY` و`DEBUG` و`ALLOWED_HOSTS`:**
   - في `backend/fresh_api/settings.py` (السطر 332–358) و`backend/core/checks.py`: القيمة الافتراضية لـ `DEBUG` هي `False`؛ وعندما يكون `DEBUG=False` يشترط النظام مفتاح `SECRET_KEY` صريحًا بطول لا يقل عن 50 حرفًا وتنوع لا يقل عن 10 أحرف مختلفة وخاليًا من أي أنماط افتراضية، كما يشترط `ALLOWED_HOSTS` غير فارغ وخاليًا من `*`.

---

## 6. المرحلة الخامسة: التحقق الفعلي من تطبيق Android (Android Verification)

تم تشغيل الأمر الموحد التالي:
```bash
gradle :app:testDebugUnitTest --tests "com.example.MicroTask1*" --tests "com.example.OffersScreenFilterAndPricingTest" --tests "com.example.ExampleUnitTest" :app:assembleDebug
```
كما تم تشغيل أداة بناء التطبيق الكاملة `compile_applet`.

### ملخص نتائج اختبارات Android وبناء APK:
| الحزمة / المهمة | عدد الاختبارات | الناجحة | الفاشلة | المتخطاة | الزمن | النتيجة |
|---|---:|---:|---:|---:|---:|---|
| `com.example.MicroTask1ADataIntegrityAndDiTest` | 5 | 5 | 0 | 0 | `0.515s` | **PASS** |
| `com.example.MicroTask1BApiContractAlignmentTest` | 5 | 5 | 0 | 0 | `0.130s` | **PASS** |
| `com.example.MicroTask1CAuthAndJwtIntegrationTest` | 10 | 10 | 0 | 0 | `6.382s` | **PASS** |
| `com.example.ExampleUnitTest` | 1 | 1 | 0 | 0 | `0.002s` | **PASS** |
| `:app:assembleDebug` & `compile_applet` | — | — | — | — | — | **BUILD SUCCESSFUL** |

---

## 7. المرحلة السادسة: العيوب المكتشفة والإصلاحات المنفذة (Discovered Defects & Fixes)

خلال تدقيق **Microtask 1C.1** تم اكتشاف عيبين محددين وإصلاحهما بأصغر نطاق ممكن مع إضافة اختبارات تمنع عودتهما:

1. **العيب الأول — عدم استدعاء فحص تحديد المعدل (`Rate Limit`) مباشرة داخل دالة الخدمة `register_customer_account`:**
   - **الملف والسطر:** `backend/apps/orders/auth.py` (السطر 2341).
   - **السبب:** كان فحص تحديد المعدل `@enforce_auth_rate_limit('login')` مطبقًا على واجهة DRF (`AuthRegisterView.post` في `views.py`)، لكن عند استدعاء دالة الخدمة `register_customer_account(data, request=req_adapter, conn=parent.conn)` مباشرة عبر خادم العقود `test_http_server.py` لم يكن يتم استدعاء `check_and_record_login_rate_limit(request)`.
   - **الإصلاح:** إضافة فحص `check_and_record_login_rate_limit(request)` في بداية `register_customer_account` عندما يكون `request is not None` (وهو فحص غير مكرر `Idempotent` في حال سبق استدعاؤه من وسيط أو مزخرف DRF).
   - **الاختبار المضاف:** تحديث `test_10_rate_limiting_enforced_on_repeated_failed_logins_and_refreshes` في `backend/apps/orders/tests.py` (السطر 3448–3486) للتحقق من رفض طلب التسجيل الثالث من نفس الـ IP برمز `throttled`.

2. **العيب الثاني — تغطية حالة فشل الخادم المؤقت `5xx` أثناء تجديد الرمز في اختبارات Android:**
   - **الملف والسطر:** `app/src/test/java/com/example/MicroTask1CAuthAndJwtIntegrationTest.kt` (السطر 450–555).
   - **السبب:** كان الكود في `FreshAuthInterceptor` (السطر 239) يحافظ على الرموز عند استلام خطأ `5xx`، لكن الاختبار `test_06` كان يتحقق فقط من `IOException` و`401` دون محاكاة استجابة `503 Service Unavailable`.
   - **الإصلاح والاختبار المضاف:** إضافة الجزء `Part B` في `test_06` لمحاكاة استجابة `503 Service Unavailable` من `/api/v1/auth/refresh/` والتأكد من بقاء الرموز المشفرة والجلسة المحلية سليمة دون مسحها.

---

## 8. القيود والمخاطر المتبقية (Residual Limitations & Risks)

1. **عدم توفر بيئة تشغيل Django ORM وPostgreSQL داخل حاوية بناء Android:**
   - لم يتم تشغيل الاختبارات الـ 22 المعتمدة على محرك Django ORM أو قاعدة بيانات PostgreSQL الحية داخل هذه الحاوية؛ ويلزم تشغيل `python manage.py test` في بيئة CI/CD الخاصة بالخادم المزودة بـ PostgreSQL وDjango 5.1 قبل الإطلاق الإنتاجي النهائي.
2. **التحقق من `AndroidKeyStore` العتادي:**
   - اختبارات الوحدة في JVM تستخدم `JvmAesGcmCipher` (`AES/GCM/NoPadding` 256-bit) لأن موفر `AndroidKeyStore` مرتبط بنظام تشغيل Android الفعلي.
3. **نطاق المهام اللاحقة:**
   - عمليات إتمام الطلب (`Checkout`) ومزامنة حركات المخزون لا تزال بانتظار **Microtask 1D** وفق خارطة الطريق المعتمدة.

---

## القرار النهائي للاعتماد (Final Certification Verdict)

CONDITIONALLY CERTIFIED
