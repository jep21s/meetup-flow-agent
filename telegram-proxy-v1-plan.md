# План: модуль telegram-proxy (v1)

> Рабочий документ реализации. Пишется до начала кодинга (пункт 0.1 плана),
> обновляется по ходу. Ветка: `telegram-proxy` (от `project` @ 25acddf).

## Статус

| Пункт | Состояние |
|---|---|
| 0. Git-подготовка (коммит `presentation`, ветка `telegram-proxy` от `project`) | ✅ сделано (69de927, ветка от 25acddf) |
| 0.1 Этот файл плана | ✅ сделано (a75380f; kill-switch §6.1 добавлен позже) |
| 1. Изменения в main (HITL → список users) | ✅ сделано (UsersRepository, waitHuman, Schedulers) |
| 2. Composite build `telegram-proxy/` + catalog | ✅ сделано (+telegrambots 6.9.7.1) |
| 3. Прод-код модуля | ✅ сделано (все файлы §4/§5) |
| 4. Тесты модуля | ✅ 10/10 (NotifyRouteTest 6, IncomingTgMessageHandlerTest 4) |
| 5. Wrapper-репозиторий Railway + deploy-скрипт | ✅ сделано (`~/projects/my/railway-meetup-tg-proxy`, коммит f301c9f; gh отсутствует — GitHub-пуш вручную) |
| 6. Документация (AGENTS.md, README, .env.example) | ✅ сделано (+ clean-all.sh) |
| 7. Проверка сборки/тестов/fatJar, финальный коммит | ✅ build/fatJar/тесты 111+10 зелёные |

Примечание по ходу: в NotifyRouteTest тестовое приложение собирается через
`Application`-extension (`application { testProxyApp() }`, паттерн FlowRouteTest) —
регистрация вложенных роутов через хелпер на `ApplicationTestBuilder` в Ktor 3.5
давала 404 на авторизованные запросы.

## 1. Цель и роли

`telegram-proxy` — отдельное Kotlin/Ktor-приложение (по образцу `foreign-messenger-application`
из messenger-adapter), которое стоит **на Railway** (не рядом с meetup-flow-agent) и связывает
Telegram-бота с агентом:

```
Telegram (группа/лички/канал)
   │  long polling (telegrambots)            ← публичный URL НЕ нужен
   ▼
telegram-proxy (Railway, :${PORT:8082})
   │  POST {MEETUP_FLOW_URL}/api/messages     Authorization: <MEETUP_FLOW_TOKEN> (raw)
   ▼
meetup-flow-agent (Ktor :8090, сервер) ── LLM-флоу, HITL, outbox
   │  POST {PROXY_BASE_URL}/api/notify        Authorization: Bearer {PROXY_TOKEN}
   ▼
telegram-proxy → Bot API → получатели
```

Два направления:
- **Вход**: апдейт Telegram → агент. Прокси НЕ разбирает содержимое: сериализует
  **всю** DTO `Update` в JSON и отправляет как `text`. Обработку сырого JSON делает агент.
- **Выход**: уведомления агента → Bot API → чаты.

## 2. Контракты (существующие, не меняем)

### 2.1 Вход: `POST /api/messages` (main, `MessageRoute.kt`)

```http
POST {MEETUP_FLOW_URL}/api/messages
Authorization: <APP_TOKEN>          ← RAW, без "Bearer" (так требует TokenAuth main)
Content-Type: application/json

{
  "idempotencyKey": "tg-<update.updateId>",
  "text": "<JSON всей DTO Update целиком>",
  "meta": {
    "authorUsername": "<from.username>",
    "chatTitle": "<chat.title>",
    "receivedAt": "<ISO-8601 из message.date>"
  }
}
```

Ответы: `202 {flowId}` — флоу создан; `409 {flowId}` — дубль idempotencyKey
(после рестарта прокси повторов не будет лишних) — лог info; `400` (напр. текст
> 10 000 симв.) — лог warn. Прокси делает 2 ретрая на 5xx/сеть (апдейт одноразовый).

### 2.2 Выход: `POST /api/notify` (прокси принимает; вызывают `HttpProxyNotifier` и `TelegramProxyTransport`)

```http
POST {PROXY_BASE_URL}/api/notify
Authorization: Bearer {PROXY_TOKEN}

{
  "flowId": "<uuid>",
  "event": "HUMAN_INPUT_REQUIRED | REMINDER | FLOW_FAILED | EVENT_PUBLISHED",
  "userIds": [<telegram_user_id>, ...],   // [] — общий канал
  "text": "...",
  "options": ["да", "нет"],               // только у HUMAN_INPUT_REQUIRED
  "deliveryId": "<uuid>"                  // только у EVENT_PUBLISHED (outbox, at-least-once)
}
```

### 2.3 Ответ человека (прокси → main): `POST /api/flows/{id}/responses`

```json
{ "responderUserId": <telegram_user_id>, "answer": "<текст опции или ответ>" }
```

`202` — ответ принят (первый побеждает), `409` — уже отвечено / флоу не ждёт ответа.
`responderUserId` в main не валидируется — фиксируется в `human_requests` для аудита,
поэтому передаём Telegram user id нажавшего кнопку.

### 2.4 Адресация сообщений из агента (решение по HITL: вопрос — в лички из списка users)

| Событие | `userIds` в main сегодня | Куда шлёт прокси |
|---|---|---|
| `HUMAN_INPUT_REQUIRED` | `emptyList()` → **меняем на `activeUserIds()`** (п. 3) | личный чат каждому из `userIds` (ботом, с кнопками) |
| `REMINDER`, `FLOW_FAILED` | `activeUserIds()` — таблица `users` | личные чаты; список пуст → общий канал |
| `EVENT_PUBLISHED` | `[]` (анонс) | общий канал `telegram.main.chat-id` |

Fallback-цепочка прокси: `userIds` непуст → лички (403 «юзер не жал /start» — warn,
едем дальше); пуст → общий канал; не задан канал — warn, доставка считается
невозможной (для outbox-события вернём 500 → ретрай).

## 3. Изменения в main (минимальные, только для HITL-адресации)

1. Новый `UsersRepository` (в `db/`): `activeTelegramUserIds(): List<Long>` —
   `SELECT telegram_user_id FROM users WHERE is_active = true`; сбой БД → пустой
   список + warn (переносим приватный `activeUserIds()` из `Schedulers.kt`).
2. `AgentFlowService.waitHuman(...)` (~строка 691):
   `userIds = emptyList()` → `userIds = usersRepository.activeTelegramUserIds()`.
3. `Schedulers.kt`: `activeUserIds()` заменяем на репозиторий (поведение то же).
4. Наполнение `users` — ручной SQL (документируем в README):
   ```sql
   INSERT INTO users (id, telegram_user_id, display_name, role)
   VALUES (gen_random_uuid(), <tg_user_id>, '<Имя>', 'member');
   ```

Таблица `users` уже существует (changeSet `080-users`, комментарий «для HITL-роутинга
и аудита ответов»), сида и писателя нет — только чтение.

## 4. Новый composite build `telegram-proxy/`

Структура (упрощённый `foreign-messenger-application`: один мессенджер — один subproject):

```
telegram-proxy/
├── settings.gradle.kts        # pluginManagement includeBuild("../gradle-plugin"),
│                              #   catalog from ../gradle/libs.versions.toml,
│                              #   TYPESAFE_PROJECT_ACCESSORS, foojay 1.0.0
├── build.gradle.kts           # group=org.jep21s.meetupflowagent.telegramproxy,
│                              #   version=getGitVersion(), kotlin-jvm alias apply false
├── deploy-railway.sh          # сборка fatJar → копия в wrapper-репо → commit+push
├── Dockerfile                 # локальная проверка образа (prebuilt jar, как корневой)
├── .env.example               # все ENV прокси
└── main/
    ├── build.gradle.kts       # build-jvm, idea-custom-plugin, build-koin,
    │                          #   alias(libs.plugins.koin.compiler), application,
    │                          #   jvmToolchain(25), fatJar "all", useJUnitPlatform + --add-opens
    └── src/
        ├── main/kotlin/org/jep21s/meetupflowagent/telegramproxy/
        │   ├── Main.kt
        │   ├── config/TelegramProxyBeanConfig.kt
        │   ├── config/TokenAuth.kt
        │   ├── config/RestModule.kt
        │   ├── route/NotifyRoute.kt
        │   ├── telegram/MessengerBot.kt
        │   ├── telegram/TgMessageSender.kt
        │   ├── telegram/DummyTgMessageSender.kt
        │   ├── telegram/UpdateEventRelay.kt
        │   ├── telegram/HitlPendingStore.kt
        │   ├── telegram/IncomingTgMessageHandler.kt
        │   └── integration/MeetupFlowAgentClient.kt
        ├── main/resources/config.properties
        └── test/kotlin/org/jep21s/meetupflowagent/telegramproxy/
            ├── route/NotifyRouteTest.kt
            └── telegram/IncomingTgMessageHandlerTest.kt
```

Изменения в корне репо:
- `settings.gradle.kts`: + `includeBuild("telegram-proxy")`.
- `gradle/libs.versions.toml`: versions + `telegrambots = "6.9.7.1"`,
  libraries + `telegrambots = { module = "org.telegram:telegrambots", version.ref = "telegrambots" }`.
- `clean-all.sh`: чистить `telegram-proxy`, если скрипт перечисляет модули.

Зависимости `main` (все версии из каталога; стартеры GA-координатами без версии):
стартеры `org.jep21s.meetupflowagent.libs:{jackson,config,logging}-starter`,
`libs.bundles.kotlinx.coroutines`, `libs.koin.ktor`, `libs.bundles.ktor.server`,
`libs.ktor.client.core`, `libs.ktor.client.cio`, `libs.ktor.client.content.negotiation`,
`libs.telegrambots`; тесты: `libs.bundles.junit`, `libs.mockk`,
`libs.kotlinx.coroutines.test`, `libs.test.ktor.server.host`.

## 5. Прод-код: назначение файлов

### Main.kt
`@KoinApplication(modules = [TelegramProxyBeanConfig::class]) class Main`;
`startKoin<Main> {}` ДО старта Ktor; `embeddedServer(CIO, port = ${PORT:8082}.toInt())`.

### config/TelegramProxyBeanConfig.kt
`@Module @Configuration @ComponentScan("org.jep21s.meetupflowagent.telegramproxy")`
+ бин `@Named("applicationCoroutineScope")` (SupervisorJob + Dispatchers.Default +
CoroutineExceptionHandler + shutdown-hook cancel — как в `MainBeanConfig` main).

### config/TokenAuth.kt
Копия маленького файла из application/main (отдельный build не видит projects.main):
`UnauthorizedException` + `Route.requireTokenAuth(expectedHeaderValue)` — сравнение
заголовка Authorization с ОЖИДАЕМЫМ ЗНАЧЕНИЕМ ЦЕЛИКОМ (для /api/notify это
`"Bearer ${proxy.token}"`).

### config/RestModule.kt
ContentNegotiation(`JacksonConfig.customizer`), StatusPages (UnauthorizedException→401,
Throwable→500), DefaultHeaders, DoubleReceive. Роуты: `GET /` "Hello World!",
`GET /ping` "pong" (healthcheck Railway), `route("/api") { requireTokenAuth(...); notify() }`.

### route/NotifyRoute.kt
- `NotifyRequestDto(flowId: UUID, event: String, userIds: List<Long> = emptyList(),
  text: String, options: List<String> = emptyList(), deliveryId: String? = null)`.
- Дедуп `deliveryId`: bounded in-memory set (LinkedHashSet, cap ~10k, at-least-once от outbox).
- `HUMAN_INPUT_REQUIRED`: `sendQuestion(chatId, text, options)` каждому `userIds`
  (InlineKeyboard, callback_data = индекс опции — ≤64 байт) + `HitlPendingStore.register(...)`.
- Прочие события: `sendText(chatId, text)` по `userIds`; пусто → общий канал.
- Ответ 202 всегда, КРОМЕ: все отправки упали → 500 (outbox повторит по своей шкале).

### telegram/MessengerBot.kt
`class MessengerBot : TelegramLongPollingBot(botToken), TgMessageSender`;
`registerBot` в `init {}` выполняется ТОЛЬКО при `telegram.bot.enabled=true`
(дефолт false — тесты/локальный запуск бота не поднимают, см. §6.1);
при выключенном флаге `TgMessageSender` = `DummyTgMessageSender` (только лог).
`onUpdateReceived` → `UpdateEventRelay.accept(update)`.
Методы: `sendText(chatId, text): Message?`, `sendQuestion(chatId, text, options): Message?`,
`answerCallback(callbackQueryId)`, `editQuestionAnswered(chatId, messageId, answer)`.
Parse mode — НЕТ (plain text: анонсы с URL/эмодзи не должны ломаться о Markdown).

### telegram/UpdateEventRelay.kt
`object` с `MutableSharedFlow<Update>(replay=0, extraBufferCapacity=128,
onBufferOverflow=DROP_OLDEST)` — мост из блокирующего колбэка telegrambots в корутины
(как в foreign).

### telegram/HitlPendingStore.kt
In-memory соответствие заданных вопросов флоу:
ключ `(chatId: Long, messageId: Long)` → `PendingQuestion(flowId, options, createdAt)`;
обратный индекс `flowId → keys` (для очистки после 202/409). TTL-очистка раз в час на
applicationCoroutineScope (`hitl.pending.ttl-hours`, default 24). Синглтон Koin.

### telegram/IncomingTgMessageHandler.kt
`@Singleton(createdAtStart)`, в `init` собирает `UpdateEventRelay.updates` на
applicationCoroutineScope. Порядок обработки апдейта:
1. `update.callbackQuery != null` (клик по кнопке): pending по
   `(callback.message.chat.id, callback.message.messageId)` →
   `MeetupFlowAgentClient.submitHumanResponse(flowId, callback.from.id, options[idx])`;
   `answerCallback` + правка исходного сообщения («✅ Ответ принят: ...»); снятие pending
   по flowId (первый ответ побеждает — main гарантирует).
2. `message.reply_to_message` совпал с pending-вопросом → то же, но
   `answer = message.text`.
3. `message` / `channelPost` (не bot-команды `/...`): сырой passthrough —
   `sendMessage(idempotencyKey="tg-${update.updateId}",
                text = jacksonMapper.writeValueAsString(update),   // ВСЯ DTO
                meta = {authorUsername, chatTitle, receivedAt})`.
4. Прочие типы апдейтов (`edited_message`, `my_chat_member`, …) — debug-лог, skip.

### integration/MeetupFlowAgentClient.kt
`HttpClient(CIO)` + `ContentNegotiation { jackson { JacksonConfig.customizer } }` +
`HttpTimeout` (connect 5с / request 30с), `expectSuccess = false`.
- `sendMessage(...)`: POST `/api/messages`, `Authorization = meetup-flow.token` (RAW).
  202 → info(flowId); 409 → info «дубль»; 400 → warn; 5xx/сеть → 2 ретрая, потом warn.
- `submitHumanResponse(flowId, responderUserId, answer)`:
  POST `/api/flows/{flowId}/responses`; 202 ок; 409 — «уже отвечено» (info, pending снять).

## 6. Конфиг

`telegram-proxy/main/src/main/resources/config.properties` (формат `${ENV:default}`):

```properties
server.port=${PORT:8082}
telegram.bot.token=${TELEGRAM_BOT_TOKEN:}
telegram.bot.username=${TELEGRAM_BOT_USERNAME:}
# ВЫКЛЮЧАТЕЛЬ БОТА: по умолчанию false — в тестах и при локальном запуске
# бот НЕ запускается (никаких вызовов Bot API / long polling). На Railway
# задаётся TELEGRAM_BOT_ENABLED=true.
telegram.bot.enabled=${TELEGRAM_BOT_ENABLED:false}
telegram.main.chat-id=${TELEGRAM_MAIN_CHAT_ID:}
telegram.source.chat-id=${TELEGRAM_SOURCE_CHAT_ID:}
telegram.source.topic-id=${TELEGRAM_SOURCE_TOPIC_ID:}
proxy.token=${PROXY_TOKEN:}
meetup-flow.url=${MEETUP_FLOW_URL:http://localhost:8090}
meetup-flow.token=${MEETUP_FLOW_TOKEN:change-me-token}
hitl.pending.ttl-hours=${HITL_PENDING_TTL_HOURS:24}
```

### 6.1 Kill-switch бота (`telegram.bot.enabled`)

- **Дефолт `false`**: юнит-тесты и локальный запуск (`./gradlew :telegram-proxy:main:run`)
  поднимают только Ktor-сервер — `registerBot`/long polling не вызываются ни разу,
  внешних вызовов Telegram нет. Отправка уведомлений при выключенном боте идёт через
  `DummyTgMessageSender` (только лог) — `/api/notify` и HITL-логика проверяются без бота.
- **`true` только на Railway** (`TELEGRAM_BOT_ENABLED=true` в ENV сервиса): бин
  `MessengerBot` регистрируется в `TelegramBotsApi` (createdAtStart) → long polling.
- Вкл + пустой `TELEGRAM_BOT_TOKEN` → fail-fast при старте с понятным сообщением.
- Тесты НЕ переопределяют флаг: дефолтного `false` достаточно, тестовый контекст
  физически не может уйти в Telegram.

`PROXY_TOKEN` — общий секрет с main (`proxy.token` там же). `TELEGRAM_MAIN_CHAT_ID` —
chat id канала/группы анонсов (бот должен быть там участником). Railway подставляет
`PORT` сам. `telegram-proxy/.env.example` — те же переменные с комментариями.

Валидация при старте: `telegram.bot.enabled=true` + пустой `TELEGRAM_BOT_TOKEN` →
fail-fast с понятным сообщением (getRequiredProperty).

## 7. Тесты (`telegram-proxy/main/src/test`)

- `NotifyRouteTest` (testApplication + mockk TgMessageSender):
  401 без заголовка / с неверным Bearer; 202 + `sendText(mainChatId)` при пустых
  `userIds`; повторный `deliveryId` → вторая отправка не происходит;
  `HUMAN_INPUT_REQUIRED` → `sendQuestion` каждому userId + pending зарегистрирован.
- `IncomingTgMessageHandlerTest` (mockk MeetupFlowAgentClient):
  текстовое сообщение → клиент вызван с `text` = сериализованный апдейт (assert
  `startsWith("{\"update_id\"")`) и `idempotencyKey = "tg-<updateId>"`;
  callback по pending → `submitHumanResponse(flowId, fromId, "опция")`;
  `/start` не пересылается.

## 8. Деплой на Railway: НОВЫЙ wrapper-репозиторий

Паттерн `railway-tg-application` (проверено: там так живёт foreign 6+ месяцев).

1. `~/projects/my/railway-meetup-tg-proxy/` — новый git-репозиторий:
   - `Dockerfile`:
     ```dockerfile
     FROM mirror.gcr.io/amazoncorretto:25-alpine-jdk
     COPY *-all.jar /app/
     COPY entrypoint.sh /app/
     RUN chmod +x /app/entrypoint.sh
     EXPOSE 8082
     WORKDIR /app
     ENTRYPOINT ["/app/entrypoint.sh"]
     ```
   - `entrypoint.sh`: `exec java -DSERVICE_NAME=telegram-proxy -jar /app/main-*-all.jar`
     (wildcard — не нужен sed при смене версии).
   - `.gitignore`: `/.idea/`
   - `README.md`: ENV для Railway-сервиса (см. п. 6) + настройка PROXY_BASE_URL в main.
2. GitHub: `gh repo create jep21s/railway-meetup-tg-proxy --private --source ... --push`
   (если gh аутентифицирован; иначе вручную). Railway-сервис и домен заводит владелец.
3. `telegram-proxy/deploy-railway.sh` (в ЭТОМ репо, аналог deploy-foreign-messenger.sh):
   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   RAILWAY_DIR="${RAILWAY_DIR:-$HOME/projects/my/railway-meetup-tg-proxy}"
   ./gradlew :telegram-proxy:main:fatJar
   JAR="$(ls -t telegram-proxy/main/build/libs/*-all.jar | head -1)"
   cp "$JAR" "$RAILWAY_DIR/"
   cd "$RAILWAY_DIR"
   git add -A && git commit -m "$(basename "${JAR%-all.jar}")" && git push origin main
   ```
4. На стороне main (env сервера): `PROXY_BASE_URL=https://<railway-домен>`,
   `PROXY_TOKEN=<тот же секрет>`.

## 9. Документация

- `AGENTS.md`: telegram-proxy в дереве архитектуры; команды
  (`./gradlew :telegram-proxy:main:build|fatJar|run`, `deploy-railway.sh`);
  REST модуля (`GET /`, `GET /ping`, `POST /api/notify`); таблица ENV.
- `README.md`: раздел «Telegram-прокси» — роли, адресация (таблица п. 2.4), деплой,
  ручной SQL для `users`, связка `PROXY_BASE_URL`/`PROXY_TOKEN`.

## 10. Проверка (перед финальным коммитом)

```bash
./gradlew projects                          # telegram-proxy виден
./gradlew :telegram-proxy:main:build        # компиляция + тесты прокси
./gradlew :telegram-proxy:main:fatJar       # *-all.jar
./gradlew :application:main:build           # main собирается после п.3
# тесты main, затронутые п.3: :application:test (Schedulers/AgentFlowService)
```

Финал: коммит на ветке `telegram-proxy`, например
`feat(telegram-proxy): модуль Telegram-прокси (long polling → /api/messages, /api/notify → Bot API, HITL-кнопки)` + отдельно `feat(main): HITL-вопрос адресатам из users`.

## 11. Риски и допущения

- Ветка от `project`: CLI-модуля там нет — прокси не требует.
- Бот пишет в личку только тем, кто нажал `/start` (иначе Bot API 403 — warn, едем дальше).
- Апдейт-JSON длиннее 10 000 симв. → main ответит 400: warn в лог, дедуп сохранён.
- Если публичный URL main окажется за self-signed TLS — добавить CA-pinning по образцу
  foreign (`CaTrustSupport` + root-ca.pem) отдельным шагом; v1 ждёт нормальный TLS.
- `users` пустая → HITL/REMINDER/FLOW_FAILED уходят в общий канал (fallback), работает
  из коробки; адресная доставка появляется после ручного INSERT.
- Long polling = единственный инстанс прокси на Railway (иначе конфликт getUpdates).
