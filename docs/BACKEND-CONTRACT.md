# Покадрово: мобильный API и реальный локальный сервер

В `server/` находится **реализация** API на Node.js 22.13+ / SQLite. При развёртывании под надёжным HTTPS-адресом этот сервер обслуживает нативное приложение и отдельную страницу веб-админки. Этот сервер **не** является клоном недоступного Sites/Cloudflare-проекта и не содержит пользователей со старого сайта.

## Запуск сервера

```sh
cd server
cp .env.example .env
# задайте собственные значения в .env
node --env-file=.env --experimental-sqlite index.mjs
```

Откройте `http://localhost:3080` и `http://localhost:3080/admin.html`. Для создания администратора:

```sh
ADMIN_INITIAL_PASSWORD='надежный_пароль_от_12_символов' node --env-file=.env --experimental-sqlite admin.mjs owner@example.com
```

Потребуются Node.js >=22.13, обратный HTTPS-прокси, приватное хранилище SQLite и настоящие секреты. Перед публичным запуском настройте firewall, мониторинг, резервное копирование базы и HTTPS. В приложение передаётся только публичный HTTPS-адрес, например `gradle :app:assembleDebug -Ppokadrovo.apiBase=https://api.example.org`.

## API (именно эти адреса используют Android-клиент и веб-интерфейс)

| Метод | Путь | Защита / смысл |
|---|---|---|
| GET | `/api/health` | Проверка состояния сервера |
| GET | `/api/config` | Доступность провайдеров и тарифов |
| POST | `/api/auth/register` | `{email,password}` → токен сессии |
| POST | `/api/auth/login` | `{email,password}` → токен сессии |
| POST | `/api/auth/logout` | Отзыв bearer-токена |
| GET | `/api/auth/me` | Bearer-токен → профиль и роль |
| POST | `/api/auth/reset/request` | `{email}` → нейтральный ответ; письмо только с Resend |
| POST | `/api/auth/reset/confirm` | `{token,password}` → смена пароля |
| GET | `/api/auth/oauth/{google\|vk}/start` | Генерация server-side `state` и ссылки OAuth; `mode=app&challenge=<PKCE S256>` для Android |
| GET | `/api/auth/oauth/{google\|vk}/callback` | Обмен OAuth-кода на сервере |
| POST | `/api/auth/oauth/exchange` | `{code,verifier}` (verifier требуется для Android) → bearer токен |
| GET | `/api/orders` | Заказы вошедшего пользователя |
| POST | `/api/orders` | `{service_id}` → заказ `pending` |
| POST | `/api/track/visit` | `{visitor_id}` → дедупликация посетителя/дня |
| GET | `/api/admin/users?page=1&search=text` | Только роль `admin`, пагинация по 50 |
| GET | `/api/admin/stats?days=30` | Только роль `admin`, реальные посещения, регистрации, покупатели, заказы и выручка |
| GET | `/api/admin/orders` | Только роль `admin`, заказы |
| POST | `/api/admin/orders/{uuid}/confirm` | Только роль `admin`, **ручное** подтверждение оплаты |

Базовые схемы ответов:

```json
{"token":"opaque-session-token","user":{"id":"...","email":"...","role":"user","email_verified":false,"created_at":0}}
```

```json
{"summary":{"visits":0,"registrations":0,"buyers":0,"paid_orders":0,"revenue_minor":0},"days":[{"date":"2026-10-15","visits":0,"registrations":0,"paid":0,"revenue_minor":0}]}
```

Токены находятся в SQLite в виде хэшей SHA-256; пароли хэшируются `scrypt` с индивидуальной солью. Только сервер определяет права администратора; менять роль в приложении невозможно. В текущей версии **нет** подтверждения почты (поле `email_verified` зарезервировано), интеграции реальной платёжной системы, автоматического подтверждения платежей и синхронизации с облачным проектом.

**Важно перед продакшеном:** развернуть сервер за HTTPS; настроить корректные Google/VK OAuth-клиенты и callback-URL; выполнить аудит потока OAuth VK; защитить endpoint учёта посещений от ботов/накрутки; ввести верификацию почты, антивзломные ограничения, аудит действий администратора, политику персональных данных и собственные тесты реальной оплаты. Android custom scheme `pokadrovo://auth` можно перехватить, поэтому используется привязка к одноразовому PKCE-коду, но для продакшена предпочтительнее verified HTTPS App Links.

## Честные ограничения восстановления

Android APK не хранит исходники рабочего сайта, актуальную Cloudflare D1-базу, доступ к пользователям, OAuth-секреты и покупкам. Этот API — новая отдельная база: данные старого сайта появятся только после легальной миграции. В тестовом окружении выполняются интеграционные проверки серверных маршрутов, но нет доступа к Android SDK для полной сборки APK.
