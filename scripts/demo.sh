#!/usr/bin/env bash
# ДЗ3 demo: SSE-стриминг агентского прогона — POST /api/messages с Accept: text/event-stream.
# Видны события reasoning_delta → tool_call → tool_result → content_delta → final.
#
# Требования: заполненный .env в корне репозитория (LLM_API_KEY, APP_TOKEN — см. .env.example),
# свободный порт 8090.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Ошибка: .env не найден в корне репозитория (образец — .env.example)" >&2
  exit 1
fi

set -a; source .env; set +a
: "${APP_TOKEN:?APP_TOKEN не задан в .env}"
: "${LLM_API_KEY:?LLM_API_KEY не задан в .env}"

BASE_URL="${APP_BASE_URL:-http://localhost:8090}"
LOG_FILE="$(mktemp /tmp/meetup-agent-demo.XXXXXX.log)"

echo "Запускаю приложение (лог: $LOG_FILE)..."
./gradlew :application:main:run >"$LOG_FILE" 2>&1 &
GRADLE_PID=$!
# гасим именно JVM приложения по порту: gradle-ленчер и демон переживают демо
trap 'fuser -k 8090/tcp 2>/dev/null || true; kill "$GRADLE_PID" 2>/dev/null || true' EXIT

echo "Жду готовности $BASE_URL ..."
READY=0
for _ in $(seq 1 90); do
  if curl -sf "$BASE_URL/" >/dev/null 2>&1; then READY=1; break; fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "Приложение упало на старте, последние строки лога:" >&2
    tail -30 "$LOG_FILE" >&2
    exit 1
  fi
  sleep 1
done
if [ "$READY" != "1" ]; then
  echo "Приложение не поднялось за 90с, последние строки лога:" >&2
  tail -30 "$LOG_FILE" >&2
  exit 1
fi

# Реальное сообщение из группы: города нет в тексте, адрес и ссылка дают данные
MESSAGE='Бесплатный митап «PiterJS #61» — 2 октября, 18:30, Санкт-Петербург, Севкабель Порт '\
'(Кожевенная линия, 40). Программа и регистрация: https://habr.com/ru/companies/piterjs/'

echo
echo "=== POST $BASE_URL/api/messages (Accept: text/event-stream) ==="
# Authorization — сырой app.token из .env (без префикса Bearer)
curl -sN -X POST "$BASE_URL/api/messages" \
  -H "Accept: text/event-stream" \
  -H "Authorization: $APP_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"$MESSAGE\"}"
echo
echo "=== конец SSE-потока ==="
