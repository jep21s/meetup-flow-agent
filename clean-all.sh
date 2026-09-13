#!/usr/bin/env bash
# Clean всех модулей всех composite builds одним вызовом.
set -euo pipefail

./gradlew \
  :application:main:clean \
  :libs:jackson-starter:clean \
  :libs:logging-starter:clean \
  :libs:config-starter:clean \
  :libs:lib-konvert:clean \
  :gradle-plugin:clean
