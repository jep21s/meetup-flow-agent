# ДЗ2 — Инструменты агента: `fetch_web_page`

## 1. Постановка

Агент обрабатывает сообщения о митапах: часть данных (дата, место, программа, регистрация)
может отсутствовать в тексте, но быть на странице по ссылке из сообщения. Реализован
вызов внешнего инструмента через function calling: LLM сама решает, когда нужно загрузить
страницу, получает текст как observation и продолжает извлечение. Код:

- интерфейс инструмента — `application/main/.../agent/tools/AgentTool.kt`
  (`AgentTool`, `ToolResult.Success|Error`, политики `tool.<name>.policy`);
- реализация — `agent/tools/FetchWebPageTool.kt`;
- клиент LLM (OpenAI-compatible, Z.AI) — `llm/KtorOpenAiLlmClient.kt` с классификацией
  ошибок (RETRYABLE/FATAL/PARSE);
- мини-ReAct-цикл — `agent/SyncAgentService.kt`: `POST /api/messages {text}` →
  `{reply, toolCalls[], iterations, limitReached}` (до 4 итераций).

## 2. JSON-схема функции

```json
{
  "type": "object",
  "properties": {
    "url": {
      "type": "string",
      "format": "uri",
      "description": "Абсолютный http(s)-URL страницы (например, страница регистрации митапа)"
    }
  },
  "required": ["url"]
}
```

Схема валидна по JSON Schema draft-07 — проверяется тестом
`FetchWebPageToolTest.parameters schema is a valid draft-07 json schema`
(networknt json-schema-validator: валидный инстанс проходит, отсутствие `url` и неверный
тип отклоняются).

## 3. SOP использования инструмента

**Когда использовать.** В сообщении есть ссылка, а обязательных данных (дата, место,
доклады, регистрация) не хватает. Один URL за вызов; если ссылок несколько — вызовы
повторяются по мере необходимости (в рамках лимита итераций).

**Ограничения (встроены в инструмент).**

- только схемы http/https;
- SSRF-защита: хост резолвится, все A/AAAA-адреса проверяются; loopback, private
  (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), unique-local IPv6
  (fc00::/7), unspecified — блокируются; проверка повторяется на каждом редиректе
  (редиректы обрабатываются вручную, максимум 3);
- таймауты: connect 5s, весь запрос 15s; тело — не больше 1 MB;
- Content-Type только text/html, text/plain, application/xhtml+xml;
- HTML → текст (Jsoup), нормализация пробелов, обрезка до 4000 символов.

**Обработка результата и ошибок.** Результат и любая ошибка возвращаются модели как
observation — решение «повторить другую ссылку / продолжить без данных / спросить
человека / завершить» принимает модель. Коды ошибок: `TIMEOUT`, `HTTP_4XX`, `HTTP_5XX`,
`TOO_LARGE`, `REDIRECT_LIMIT`, `SSRF_BLOCKED`, `UNSUPPORTED_CONTENT_TYPE`, `DNS`,
`NETWORK`, `INVALID_URL`, `INVALID_ARGS`. Ошибки инструмента не роняют запрос —
в ответе API они видны в `toolCalls[].ok/errorCode`.

## 4. Как запустить и проверить

```bash
# 1) окружение: скопировать .env.example → .env, заполнить LLM_API_KEY (Z.AI)
cp .env.example .env   # затем отредактировать

# 2) тесты (без реального LLM: FakeChatClient, embedded-сервер, MockEngine)
./gradlew :application:test:test

# 3) запустить приложение и проверить вручную
set -a; source .env; set +a
./gradlew :application:main:run &
curl -s -X POST http://localhost:8090/api/messages \
  -H "Authorization: $APP_TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"Митап про Kotlin, 25 сентября 19:00, СПб, бесплатно, регистрация: https://example.com/meetup"}'
```

Ожидаемый ответ: JSON c полем `reply` (итоговое структурированное JSON агента) и
`toolCalls` — список вызовов `fetch_web_page` с флагом `ok`. Без заголовка
`Authorization` — 401; пустой текст или длиннее 10 000 символов — 400.
