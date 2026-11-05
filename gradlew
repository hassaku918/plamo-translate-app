#!/bin/sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading gradle-wrapper.jar..."
  mkdir -p "$DIR/gradle/wrapper"
  curl -fsSL -o "$JAR" \
    "https://github.com/gradle/gradle/raw/v8.9.0/gradle/wrapper/gradle-wrapper.jar" \
    || curl -fsSL -o "$JAR" \
    "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
fi
if command -v gradle >/dev/null 2>&1 && [ ! -f "$JAR" ]; then
  exec gradle "$@"
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
