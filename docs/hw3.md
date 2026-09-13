# ДЗ3 — Подключение контекста и streaming

## Постановка

ДЗ3 требует: (1) источник контекста (файл или API), подключённый к агенту;
(2) streaming ответа модели наружу; (3) простой воспроизводимый сценарий;
(4) зафиксированная архитектура подключения контекста.

## Архитектура подключения контекста

```
context/meetup-examples.md ──▶ FileContextProvider ──▶ SystemPromptBuilder ─┐
          (few-shot пары «сообщение → итоговый JSON»)   (склейка с базой)   │
prompts/extractor-system.md ────────────────────────────────────────────────┤
                                                                            ▼
  user text ──▶ ConversationState ──▶ ChatCompletionRequest(messages) ──▶ LLM
                  ▲    (system + user + assistant(tool_calls) + tool)      │
                  └────────────── tool-наблюдения (Observe) ◀── fetch_web_page
```

- **`ContextProvider`** (`agent.context`) — интерфейс источника контекста.
  Реализация ДЗ3 — `FileContextProvider`: читает `context/meetup-examples.md`
  (4 few-shot примера: happy-path; сообщение только со ссылкой; платное →
  REJECTED; город, выводимый из адреса). Источник меняется (API, БД) новой
  реализацией интерфейса без правки промпта.
- **`SystemPromptBuilder`** — единственное место склейки системного промпта:
  `extractor-system.md` (база) + блок `# Примеры` из провайдера.
- **`ConversationState`** — контекст цикла: вся история сессии (system + user +
  assistant tool_calls + tool-наблюдения) передаётся в `messages` на каждом
  вызове LLM — модель видит весь ход флоу, а не последнее сообщение.

## Streaming

- **`LlmClient.streamChat(request, onDelta)`** — стриминговый вызов
  `chat/completions` (`stream=true`); SSE-парсинг (`data: {chunk}` /
  `data: [DONE]`) вручную по строкам. Дельты (`StreamDelta`):
  `ReasoningDelta` (GLM `reasoning_content`), `ContentDelta`, `ToolCallDelta`
  (аргументы приходят кусками и наращиваются по `index`), `Finish`.
  Параллельно с эмиссией накапливается полный ответ — по завершении
  возвращается собранный `ChatCompletionResponse` (эквивалент `complete`).
- **`SyncAgentService.processStream`** — тот же ReAct-цикл, но дельты модели
  пробрасываются наружу событиями; исполнение тулов даёт `tool_call` /
  `tool_result`; в конце — `final`.
- **Роут**: `POST /api/messages` с `Accept: text/event-stream` отвечает
  SSE-потоком; без Accept — прежний JSON-ответ (обратная совместимость).
  Сессия живёт в рамках запроса; replay-SSE по id — этап `project`.

События SSE:

| event | data | когда |
|---|---|---|
| `reasoning_delta` | `{"text":"..."}` | дельта reasoning_content модели |
| `content_delta` | `{"text":"..."}` | дельта финального контента |
| `tool_call` | `{"name","arguments"}` | модель вызвала тул |
| `tool_result` | `{"ok","text","code"}` | результат/ошибка тула |
| `final` | `{"reply","toolCalls",...}` | итог прогона |
| `error` | `{"message"}` | сбой флоу после старта стрима |

## Как воспроизвести

```bash
cp .env.example .env   # заполнить LLM_API_KEY, APP_TOKEN
./scripts/demo.sh
```

Скрипт поднимает приложение (`gradle :application:main:run`), шлёт реальное
сообщение о митапе со ссылкой и печатает SSE-поток; по завершении гасит
приложение.

## Что смотреть на скрине/видео

В терминале после запуска `./scripts/demo.sh` виден поток кадров:

```
event: reasoning_delta
data: {"text":"В сообщении есть ссылка — открою страницу..."}
...
event: tool_call
data: {"name":"fetch_web_page","arguments":"{\"url\":\"https://habr.com/...\"}"}
event: tool_result
data: {"ok":true,"text":"PiterJS — сообщество... митап 2 октября...","code":null}
event: content_delta
data: {"text":"{\"title\":\"PiterJS"}
...
event: final
data: {"reply":"{...итоговый JSON...}","toolCalls":[{"name":"fetch_web_page",...}],"iterations":2,"limitReached":false}
```

Демонстрируется: reasoning идёт потоком до вызова тула; `tool_call` →
`tool_result` между дельтами; итоговый JSON собирается из `content_delta` и
дублируется целиком в `final`.

## Тесты

- `SystemPromptBuilderTest` — склейка базы и примеров; файлы существуют (fail-fast).
- `SseStreamParsingTest` — fixture-чанки: tool_call из 3 дельт (аргументы
  собираются в один вызов), reasoning+content вперемешку, usage из финального
  чанка, `[DONE]`, классификация 429/PARSE.
- `SyncAgentStreamRouteTest` — testApplication + FakeChatClient со
  стрим-сценарием: порядок `tool_call → tool_result → reasoning_delta →
  content_delta → final`; сценарий без тулов; ошибка флоу → событие `error`;
  POST без Accept → JSON (обратная совместимость).

`./gradlew :application:test:test` — зелёный.
