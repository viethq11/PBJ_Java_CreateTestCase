#!/usr/bin/env bash
# Spring Boot + Lombok require JDK 17 here. Default `mvn` on macOS often uses Homebrew JDK 25 → build fails → web không lên.
set -euo pipefail
cd "$(dirname "$0")"

if [[ "$(uname -s)" == Darwin ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "Cần JDK 17. Cài Temurin 17 rồi chạy:" >&2
  echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v 17)" >&2
  echo "  mvn spring-boot:run" >&2
  exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
  PORTABLE_MVN="$(pwd)/.tools/apache-maven-3.9.9/bin/mvn"
  if [[ -x "$PORTABLE_MVN" ]]; then
    echo "System 'mvn' not found. Using portable Maven from .tools."
    MVN_CMD="$PORTABLE_MVN"
  else
    echo "Error: 'mvn' not found and portable Maven missing." >&2
    exit 1
  fi
else
  MVN_CMD="mvn"
fi

echo "Using JAVA_HOME=$JAVA_HOME"
exec "$MVN_CMD" spring-boot:run "$@"
