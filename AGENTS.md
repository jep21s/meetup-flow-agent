# meetup-flow-agent

Kotlin/Ktor-сервис. Каркас: Gradle composite builds + Koin annotations + config.properties/env.

## Архитектура

```
<repo>/                        корневой settings.gradle.kts — includeBuild(application), includeBuild(libs), includeBuild(telegram-proxy)
├── gradle/libs.versions.toml  единый version catalog (все версии только здесь)
├── gradle-plugin/             5 конвеншн-плагинов: build-jvm, build-koin, konvert, build-docker, idea-custom-plugin
├── libs/                      стартеры:
│   ├── config-starter/        ConfigLoader (config.properties + ${ENV_VAR:default})
│   ├── jackson-starter/       JacksonConfig.customizer — единый ObjectMapper
│   ├── logging-starter/       logback XML (JSON / logstash encoder)
│   └── lib-konvert/           requireNotNull() extensions для Konvert
├── application/
│   ├── meetup-info-extractor/ # ВСЯ логика: agent/ llm/ guardrails/ domain/ flow/ db/
│   │                          # scheduler/ notify/ outbox/ resilience/ observability/
│   │                          # + ресурсы (prompts, db-миграции, schema, context)
│   │                          # свой Koin-модуль ExtractorBeanConfig
│   └── main/                  # ТОЛЬКО REST-слой: Main.kt, config/ (RestModule, TokenAuth,
│                              # Cors, MainBeanConfig-scope), route/; fatJar; :8090
└── telegram-proxy/            Telegram-прокси на Railway (long polling ↔ REST агента)
    ├── deploy-railway.sh      fatJar → wrapper-репо railway-meetup-tg-proxy → push
    └── main/                  Ktor :8082, бот (kill-switch), «тупая труба»:
                               форвард апдейтов в main + /api/send|callback-answer|
                               message-keyboard-remove
```

## Стек

- Kotlin 2.3.20-RC2, JVM 21, Gradle 8.14
- Ktor 3.3.3 (CIO), Koin 4.2.0 (annotations + compiler plugin), Jackson 2.18.3
- kotlin-logging (oshai) + logback + logstash encoder (JSON-логи)
- Тесты: JUnit 5 + assertj + MockK + ktor-server-test-host

## Конвенции

- **gradle-plugin НЕ в корневом settings** — каждый sub-build подключает его через `pluginManagement { includeBuild("../gradle-plugin") }`
- Стартеры подключаются GA-координатами без версии: `implementation("org.jep21s.meetupflowagent.libs:config-starter")` (composite substitution)
- Модули application подключаются через `projects.<name>` (TYPESAFE_PROJECT_ACCESSORS)
- Koin: `@Module @Configuration @ComponentScan("<пакет>")` на `*BeanConfig`, регистрация в `@KoinApplication` в `Main.kt`
- Разделение как в messenger-adapter: `application/main` — тонкий REST-слой (endpoint), вся логика — в модуле `application/meetup-info-extractor` (зависимость `projects.meetupInfoExtractor`; каждому модулю, использующему логику, — своя явная зависимость, implementation не транзитивен)
- Роуты — `fun Route.xxx()` extension в пакете `route/`, подключаются в `RestModule.kt`
- Jackson — только через `JacksonConfig.customizer` (единый конфиг для server/client/ручной сериализации)
- Конфиг — `application/main/src/main/resources/config.properties` с `${ENV_VAR:default}`, чтение через `ConfigLoader`
- Логирование — только kotlin-logging (`KotlinLogging.logger { }`)

## REST API

- `GET /` — "Hello World!" (публичный)
- `GET /api/ping` — "pong" (заголовок `Authorization: <app.token>`)

## telegram-proxy

Отдельный composite build (`telegram-proxy/`), деплой на Railway из wrapper-репо
`railway-meetup-tg-proxy` (паттерн railway-tg-application). Связывает бота Telegram
с агентом, стоит НЕ на одном сервере с application/main:

- **Прокси — «тупая труба»** (только приём/отправка, без решений): все апдейты
  long polling → `POST {MEETUP_FLOW_URL}/api/telegram/updates` (RAW
  `Authorization: <MEETUP_FLOW_TOKEN>`, тело = вся DTO Update, retry 2×);
  отправка по командам основного сервиса `POST /api/send|callback-answer|
  message-keyboard-remove` (`Authorization: Bearer {PROXY_TOKEN}`)
- **Все решения — модуль application/telegram** основного сервиса: фильтр
  источника (`telegram.source.chat-id` + `topic-id`), команды (/start), HITL
  (кнопки `hitl:<flowId>:<idx>` / reply по таблице telegram_questions →
  `human_requests.submitAnswer` + резюм флоу), passthrough в inbox extractor'а
  с `idempotencyKey = "tg-<updateId>"`; исходящая адресация (userIds → лички,
  пусто → общий канал `telegram.main.chat-id`)
- **Kill-switch бота**: `telegram.bot.enabled` дефолт **false** — тесты и локальный
  запуск бота не поднимают (отправка через DummyTgMessageSender); на Railway
  `TELEGRAM_BOT_ENABLED=true` (+ пустой токен при enabled → fail-fast)
- Long polling = **один инстанс** на Railway

REST модуля: `GET /`, `GET /ping` (healthcheck, публичный), `POST /api/send`,
`POST /api/callback-answer`, `POST /api/message-keyboard-remove` (все Bearer).

ENV прокси — `telegram-proxy/.env.example` (PORT, TELEGRAM_BOT_*, PROXY_TOKEN,
MEETUP_FLOW_URL/TOKEN); ENV телеграм-логики основного сервиса (TELEGRAM_MAIN_CHAT_ID,
TELEGRAM_SOURCE_CHAT_ID/TOPIC_ID) — в корневом `.env.example`.

Наполнение адресатов HITL — таблица `users` (main): `INSERT INTO users (id, telegram_user_id,
display_name, role) VALUES (gen_random_uuid(), <tg_id>, '<Имя>', 'member');`

## Конфигурация

`config.properties` (env-переменные):

| Свойство | Env | Default |
|---|---|---|
| `app.token` | `APP_TOKEN` | `change-me-token` |
| `cors.allowed.origins` | `CORS_ALLOWED_ORIGINS` | (пусто = CORS выключен; `*` = anyHost) |

## Команды

```bash
./gradlew projects                      # структура
./gradlew :application:main:build       # сборка + тесты
./gradlew :application:main:run         # запуск (:8090)
./gradlew :application:main:fatJar      # fat jar (для Docker)
./gradlew :application:main:test        # тесты
./gradlew :telegram-proxy:main:build    # сборка + тесты прокси (бот выключен)
./gradlew :telegram-proxy:main:run      # запуск прокси (:8082, бот выключен)
./gradlew :telegram-proxy:main:fatJar   # fat jar прокси
./telegram-proxy/deploy-railway.sh      # деплой: jar → wrapper-репо → Railway
./clean-all.sh                          # clean всех модулей
```

Docker: `./gradlew :application:main:fatJar && docker build -t meetup-flow-agent:latest .`

## Важно

- Все версии зависимостей — в `gradle/libs.versions.toml`; `kotlinVersion` дублируется в `gradle.properties`
- Konvert-плагин требует `id("com.google.devtools.ksp") version "2.3.2"` в модуле
- Тесты требуют `--add-opens` в `tasks.test` jvmArgs (уже настроено)
- Версия артефакта — из git-тега (`getGitVersion()` в `application/build.gradle.kts`), без тегов `1.0-SNAPSHOT`
