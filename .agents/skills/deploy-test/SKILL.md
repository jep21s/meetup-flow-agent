---
name: deploy-test
description: Деплой текущей фича-ветки meetup-flow-agent на тестовый контур сервера realistic — сборка fat jar, заливка в ~/realistic/test/jars/, сборка образа meetup-flow-agent-test, перезапуск meetup-flow-agent. Триггеры — «задеплой на тест», «задеплой тест», «деплой тестового контура», «выклади фичу на тест», «обнови тест».
---

## Что делает этот скилл

Полный цикл деплоя текущей фича-ветки проекта meetup-flow-agent на тестовый контур сервера realistic, без merge в main и без тегов. Работает только с тест-контуром — прод не трогает (прод-контур на realistic пока не развёрнут).

Итог: контейнер `meetup-flow-agent-test` на образе `localhost/meetup-flow-agent-test:<ветка>-<hash>`, доступен с сервера `http://127.0.0.1:8089` (через meetup-test-envoy; внешний nginx/домен на realistic пока не настроен).

Логи приложения едут на сервер mysterious (opensearch, индексы `fluent-bit-test-*`) — как их смотреть, см. скилл `server-ops` / `references/mysterious-logs.md`.

## Предусловия

- **Текущая ветка ≠ main.** Проверить: `git branch --show-current`. На main `getGitVersion()` даёт тег или `1.0-SNAPSHOT` — тестовая версия не получится. Если сейчас main:
  - есть незакоммиченные изменения (`git status --short` непуст) → создать ветку по правилам из `git-finalize` (`feature/<краткое-описание>` / `fix/<...>`, kebab-case) и закоммитить: `git add -A && git commit -m "<type>: <subject>"` (conventional commits, на английском);
  - изменений нет → просто создать ветку от HEAD: `git checkout -b <имя>`;
  - деплоить эту ветку. **Merge в main и git tag не делать** — это workflow `git-finalize`, для тест-деплоя не нужен.
- Ветка коммитнута, иначе jar получит суффикс `-dirty` (допустимо, но предупредить пользователя).
- Постgres-контур на realistic запущен (первый раз: `ssh realistic 'cd ~/realistic/postgres && podman-compose -f podman-compose.yaml up -d'`).

## Шаги

### 1. Сборка fat jar

```bash
./clean-all.sh && ./gradlew :application:main:fatJar
```

Артефакт: `application/main/build/libs/main-<ветка>-<hash8>[-dirty]-all.jar` (версию формирует `getGitVersion()` в `application/build.gradle.kts`).

### 2. Заливка jar на сервер

```bash
scp application/main/build/libs/main-<...>-all.jar realistic:'~/realistic/test/jars/'
```

### 3. Сборка образа

```bash
ssh realistic '~/realistic/test/build_image.sh'
```

Без аргумента скрипт берёт самый свежий jar в `test/jars/` (по mtime); можно передать имя jar аргументом. Скрипт тегирует образ двумя тегами: `meetup-flow-agent-test:<версия>` + `meetup-flow-agent-test:latest`. Compose-файл тест-контура использует `:latest` и не меняется.

### 4. Перезапуск meetup-flow-agent

Старый podman-compose не пересоздаёт контейнер при смене образа — обязательно down + up (секреты подхватываются через env_file, `set -a` не нужен):

```bash
ssh realistic 'cd ~/realistic/test && podman-compose -f podman-compose.yaml down meetup-flow-agent && podman-compose -f podman-compose.yaml up -d meetup-flow-agent'
```

Вывод podman-compose фильтровать маскирующим sed: `sed -E "s/(-e [A-Za-z0-9_]+=)[^ ]+/\1[MASKED]/g"` — не показывать значения env-переменных.

### 5. Проверка

```bash
ssh realistic 'sleep 10 && podman ps --filter name=meetup-flow-agent --format "{{.Status}}" && podman logs --tail 30 meetup-flow-agent-test'
```

Liveness через envoy (с сервера): `ssh realistic 'curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8089/api/health'` — ожидается 401 (нет bearer APP_TOKEN) или 404; **любой ответ приложения** означает, что цепочка жива. 502/503 — приложение не поднялось: смотреть логи, подождать 10–15 сек (прогрев JVM) и повторить.

## Важно

- Только тест-контур (`~/realistic/test/`). Прод-контур (`~/realistic/prod/`), postgres (`~/realistic/postgres/`) — не трогать.
- Jar'ы класть только в `test/jars/` (контекст сборки изолирован, в образ попадает только app.jar).
- Секреты из `test/.env` и вывод podman-compose не показывать.
- Каждый билд добавляет образ ~сотни МБ; периодически чистить: `ssh realistic 'podman rmi localhost/meetup-flow-agent-test:<старый-тег>'` (не `latest`) — только по подтверждению пользователя.
