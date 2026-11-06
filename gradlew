#!/bin/sh
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
mkdir -p "$DIR/gradle/wrapper"
if [ ! -f "$JAR" ]; then
  echo "Downloading gradle-wrapper.jar..."
  curl -fsSL -o "$JAR" \
    "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
