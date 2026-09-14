# meetup-flow-agent

Kotlin/Ktor-сервис. Каркас: Gradle composite builds + Koin annotations + config.properties/env.

## Архитектура

```
<repo>/                        корневой settings.gradle.kts — includeBuild(application), includeBuild(libs)
├── gradle/libs.versions.toml  единый version catalog (все версии только здесь)
├── gradle-plugin/             5 конвеншн-плагинов: build-jvm, build-koin, konvert, build-docker, idea-custom-plugin
├── libs/                      стартеры:
│   ├── config-starter/        ConfigLoader (config.properties + ${ENV_VAR:default})
│   ├── jackson-starter/       JacksonConfig.customizer — единый ObjectMapper
│   ├── logging-starter/       logback XML (JSON / logstash encoder)
│   └── lib-konvert/           requireNotNull() extensions для Konvert
└── application/
    └── main/                  Ktor :8090 (CIO), Koin, REST-каркас, fatJar
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
- Роуты — `fun Route.xxx()` extension в пакете `route/`, подключаются в `RestModule.kt`
- Jackson — только через `JacksonConfig.customizer` (единый конфиг для server/client/ручной сериализации)
- Конфиг — `application/main/src/main/resources/config.properties` с `${ENV_VAR:default}`, чтение через `ConfigLoader`
- Логирование — только kotlin-logging (`KotlinLogging.logger { }`)

## REST API

- `GET /` — "Hello World!" (публичный)
- `GET /api/ping` — "pong" (заголовок `Authorization: <app.token>`)

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
./clean-all.sh                          # clean всех модулей
```

Docker: `./gradlew :application:main:fatJar && docker build -t meetup-flow-agent:latest .`

## Важно

- Все версии зависимостей — в `gradle/libs.versions.toml`; `kotlinVersion` дублируется в `gradle.properties`
- Konvert-плагин требует `id("com.google.devtools.ksp") version "2.3.2"` в модуле
- Тесты требуют `--add-opens` в `tasks.test` jvmArgs (уже настроено)
- Версия артефакта — из git-тега (`getGitVersion()` в `application/build.gradle.kts`), без тегов `1.0-SNAPSHOT`
