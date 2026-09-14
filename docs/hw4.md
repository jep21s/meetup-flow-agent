# ДЗ4 — Память агента

## Архитектура памяти

```
POST /api/messages ─▶ ReAct-цикл (ДЗ2-3) ─▶ финальный JSON агента
                         │                        │
                         ▼ (внутри цикла)         ▼ (после ответа)
              ┌── search_duplicate ───┐   EventPersister (best-effort)
              │ embed(query) 768d     │   flow (PROCESSING) → дубль-чек →
              │ pgvector cosine top-5 │   insert events ИЛИ duplicate-link
              │ + WHERE дата/орг.     │        │
              └───────────┬───────────┘        ▼
                          ▼               Postgres 18 + pgvector
                     events.embedding vector(768), HNSW (vector_cosine_ops)
```

- **Хранилище** — `docker-compose.yaml` (pgvector/pgvector:pg18), схема — Liquibase
  (`db/changelog/`): все таблицы домена, у `events` колонка `embedding vector(768)` +
  HNSW-индекс косинусной близости.
- **Эмбеддинги** — `YandexEmbeddingClient` (Yandex AI Studio, OpenAI-compatible
  `/embeddings`, модель text-embeddings-v2-doc): обязательный параметр
  `dimensions=768` (дефолт модели — 256), одна строка за запрос.
- **Запись в память** — `EventPersister`: финальный JSON агента → строка `flows`
  → эмбеддинг нормализованного представления `title | organizer | дата | venue` →
  финальная проверка дубля (окно ±3 дня, порог 0.92) → insert `events` либо
  запись связи в `duplicates`. Результат возвращается в ответе API (`memory`) и
  SSE-событием `persisted`.

## Набор данных

Прогнаны 5 сообщений через `POST /api/messages` (реальная модель):

| Сообщение | Вердикт агента | Память |
|---|---|---|
| PiterJS #61, 02.10.2026, Севкабель Порт, бесплатно | APPROVED | SAVED → `events` + embedding |
| Heisenbug Popek, 14.11.2026, от 3500 ₽ | REJECTED (PAID) | SKIPPED (flow → REJECTED) |
| JPoint SPb Meetup, 21.10.2026, Кронверк, бесплатно | APPROVED | SAVED → `events` + embedding |
| GoSPb #12, 28.10.2026, ИТМО, бесплатно | APPROVED | SAVED → `events` + embedding |
| Онлайн-воркшоп «только YouTube-трансляция» | REJECTED (ONLINE_ONLY) | SKIPPED |

В БД: 3 события с 768-мерными эмбеддингами; 6 флоу со статусами
3×COMPLETED / 3×REJECTED (см. проверку дубля ниже — станет 1×DUPLICATE).

## Векторный поиск

`EventRepository.searchSimilar` — сырой SQL поверх Exposed:

```sql
SELECT id, title, starts_at, organizer, 1 - (embedding <=> ?::vector) AS similarity
FROM events
WHERE embedding IS NOT NULL
  AND starts_at BETWEEN ? AND ?      -- окно ±3 дня от даты события
  [AND organizer = ?]                -- фильтр по организатору (точно)
ORDER BY embedding <=> ?::vector
LIMIT 5
```

`<=>` — косинусное расстояние pgvector; HNSW-индекс ускоряет поиск ближайших
соседей. Это и есть гибридный поиск: **смысл** (близость эмбеддингов запроса и
события) + **связи** (фильтры по дате и организатору, а связи между объектами —
ниже).

## Связи между объектами

Схема хранит связи, а не плоский список:

- `events.flow_id → flows.id` — какое событие породил какой флоу обработки;
- `flows.inbox_message_id → inbox_messages.id` (и обратный `inbox_messages.flow_id`)
  — сообщение ↔ флоу;
- `duplicates(flow_id → flows.id, existing_event_id → events.id, similarity,
  decided_by)` — решение о дубле связывает НОВЫЙ флоу с УЖЕ существующим событием.

Проверка на демо-данных (после распознавания дубля, см. ниже):

```
SELECT d.flow_id, e.title AS existing_event, d.similarity, d.decided_by
FROM duplicates d JOIN events e ON e.id = d.existing_event_id;

              flow_id               | existing_event | similarity | decided_by
--------------------------------------+----------------+------------+------------
 c809b2fe-e97a-4ce5-b652-26ae017d714a | PiterJS #61    |          1 | AGENT
```

Группировка по организатору (графовая память «у кого какие события»):

```
SELECT organizer, count(*) FROM events GROUP BY organizer;
 GoSPb   | 1 | GoSPb #12
 JPoint  | 1 | JPoint SPb Meetup
 PiterJS | 1 | PiterJS #61
```

## Пример запроса/ответа: распознанный дубль

Повторное сообщение об **уже известном** митапе, сформулированное другими
словами (нет ни «#61» в заголовке, ни слова «митап»):

> Напоминаю: в первую пятницу октября, 2 числа, вечером в 19:00 на Кожевенной
> линии 40 в Севкабель Порт пройдёт встреча питерского JavaScript-сообщества
> PiterJS (61-я встреча). Участие бесплатное, но нужна предварительная
> регистрация на сайте piterjs.org.

`POST /api/messages` c `Accept: text/event-stream` (ключевые кадры потока):

```
event: tool_call
data: {"name":"search_duplicate","arguments":"{\"eventDate\":\"2026-10-02\",
       \"organizer\":\"PiterJS\",\"query\":\"PiterJS #61 | PiterJS | 2026-10-02 | Севкабель Порт\"}"}

event: tool_result
data: {"ok":true,"text":"{\"candidates\":[{\"title\":\"PiterJS #61\",
       \"startsAt\":\"2026-10-02T16:00:00Z\",\"organizer\":\"PiterJS\",\"similarity\":1.0}],
       \"hint\":\"similarity ≥ 0.92 — почти наверняка дубль...\"}","code":null}

event: final
data: {"reply":"{\"title\":\"PiterJS #61\",\"description\":\"Повторное сообщение об уже
       известном митапе: в календаре найден PiterJS #61 ... similarity 1.0 ...\",...,
       \"verdict\":{\"status\":\"NEEDS_REVIEW\",\"reasons\":[\"POSSIBLE_DUPLICATE\"]},...}",
       "iterations":3,...}

event: persisted
data: {"type":"DUPLICATE","existingEventId":"3eee1ef9-9a44-44e3-bd0b-a17bb4c3e743",
       "existingTitle":"PiterJS #61","similarity":1.0}
```

Что произошло: агент перед финальным ответом вызвал `search_duplicate`
(по смыслу + фильтры даты/организатора); кандидат найден с similarity 1.0
(≥ 0.92 — порог дубля); финальный вердикт — NEEDS_REVIEW/POSSIBLE_DUPLICATE;
персистер записал связь `duplicates → existing_event` и не создал второе
событие; флоу переведён в статус DUPLICATE. Тот же итог доступен без
стриминга — в JSON-ответе поле `memory.type = "DUPLICATE"`.

## Как воспроизвести

```bash
cp .env.example .env   # заполнить APP_TOKEN, LLM_API_KEY, EMBEDDING_API_KEY,
                       # EMBEDDING_FOLDER_ID, DB_PASSWORD
docker compose up -d postgres
./gradlew :application:main:run          # миграции применяются при старте
curl -s -X POST localhost:8090/api/messages \
  -H "Authorization: $APP_TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"... сообщение о митапе ..."}'          # JSON-ответ c "memory"
curl -sN -X POST localhost:8090/api/messages \
  -H "Authorization: $APP_TOKEN" -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"text":"... повтор того же митапа другими словами ..."}'   # SSE до persisted
```

## Тесты

- `SchemaMigrationTest` — миграции идемпотентны (двойной прогон), все таблицы §6,
  `vector(768)` в DDL, HNSW-индекс, CHECK-констрейнты статусов (invalid → ошибка).
- `EventRepositoryTest` — round-trip всех полей (включая vector и jsonb);
  поиск: порядок по убыванию similarity, окно дат ±3 дня, фильтр организатора,
  события без эмбеддинга игнорируются.
- `EventPersisterTest` — JSON агента → исходы: SAVED (все поля best-effort),
  DUPLICATE (порог ≥0.92, в т.ч. при NEEDS_REVIEW-вердикте агента), серая зона
  <0.92 → новое событие, REJECTED/нет полей → SKIPPED, эмбеддинг недоступен →
  WAITING_RETRY.
- `YandexEmbeddingClientTest` — тело запроса (dimensions=768, encoding_format,
  model URI из folderId, заголовок x-folder-id), парсинг 768-мерного вектора,
  401→FATAL, 429/5xx→RETRYABLE, битый JSON/не та размерность→PARSE.
- `SearchDuplicateToolTest` — кандидаты/пустой результат/INVALID_ARGS/ошибка
  эмбеддинга; окно ±3 дня и организатор передаются в репозиторий.

Постgres для тестов поднимается Testcontainers'ом (pgvector/pgvector:pg18);
внешних вызовов в `application/test` нет (FakeEmbeddingClient, моки).
`./gradlew :application:test:test` — зелёный.
