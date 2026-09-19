#!/usr/bin/env bash
# Деплой telegram-proxy на Railway: собрать fatJar → скопировать в wrapper-репо
# (railway-meetup-tg-proxy) → commit + push. Railway пересобирает контейнер по push.
# Wrapper-репо живёт ОТДЕЛЬНО от этого проекта (паттерн railway-tg-application).
set -euo pipefail

RAILWAY_DIR="${RAILWAY_DIR:-$HOME/projects/my/railway-meetup-tg-proxy}"

if [ ! -d "$RAILWAY_DIR/.git" ]; then
  echo "wrapper-repo not found: $RAILWAY_DIR (clone/create it first)" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
./gradlew :telegram-proxy:main:fatJar

# свежий *-all.jar (версия из git-тега; без тегов — 1.0-SNAPSHOT)
JAR="$(ls -t telegram-proxy/main/build/libs/*-all.jar | head -1)"
cp "$JAR" "$RAILWAY_DIR/"

cd "$RAILWAY_DIR"
# удаляем старые jar'ы — в репо лежит ровно один артефакт
ls *-all.jar | grep -v "^$(basename "$JAR")$" | xargs -r git rm -q --
git add -A
git commit -m "$(basename "${JAR%-all.jar}")"
git push origin HEAD
echo "deployed: $(basename "$JAR") pushed to $RAILWAY_DIR"
