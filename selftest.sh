#!/bin/sh
# Automated client check (needs a display): sandbox game, threaded planning with gray preview,
# commit, unit builds everything, PASS/FAIL in the log. Usage: ./selftest.sh [map[:water]]
#   ./selftest.sh <map>:boot   bootstrap from a nearly empty core, step by step (SPEED=game speed, default 3)
# The client is ~/mindustry-test/Mindustry.jar, else $MINDUSTRY_JAR, else the Steam copy on macOS; its
# data (settings, mods, screenshots) stays in ~/mindustry-test/data.
set -e
cd "$(dirname "$0")"
JAVA="$HOME/jdk17/bin/java"; [ -x "$JAVA" ] || JAVA=java # else JDK 17 on the PATH
[ -x "$HOME/jdk17/bin/java" ] && export JAVA_HOME=~/jdk17 || export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
./gradlew jar --no-daemon -q
mkdir -p ~/mindustry-test/data/mods
cp build/libs/mindustry-autobuildDesktop.jar ~/mindustry-test/data/mods/autobuild.jar
STEAM="$HOME/Library/Application Support/Steam/steamapps/common/Mindustry/Mindustry.app/Contents/Resources/desktop.jar"
JAR=~/mindustry-test/Mindustry.jar
[ -f "$JAR" ] || JAR="${MINDUSTRY_JAR:-$STEAM}"
# the Steam build quits without a running Steam client: a version.properties ahead of it on the class
# path makes it the plain release build (nothing in the Steam folder is changed)
OVERRIDE=~/mindustry-test/client-override
if unzip -p "$JAR" version.properties | grep -q modifier=steam; then
  mkdir -p "$OVERRIDE"
  unzip -p "$JAR" version.properties | sed 's/modifier=steam/modifier=release/' > "$OVERRIDE/version.properties"
else
  rm -rf "$OVERRIDE"
fi
# macOS: GLFW wants the first thread; WSLg: GL 3.3 (the default 4.6 request fails)
if [ "$(uname)" = Darwin ]; then OPTS="-XstartOnFirstThread"; ARGS=""; else OPTS=""; ARGS="-gl 3.3"; fi
# GNU timeout (Linux), else perl's alarm (macOS has no timeout)
limit(){ if command -v timeout >/dev/null; then timeout "$@"; else perl -e 'alarm shift; exec @ARGV' "$@"; fi; }
cd ~/mindustry-test
case "$1" in *:boot*) LIMIT=1500 ;; *) LIMIT=200 ;; esac
MINDUSTRY_DATA_DIR=$PWD/data limit $LIMIT "$JAVA" $OPTS -Dautobuild.selftest="${1:-Fortress}" -Dautobuild.speed="${SPEED:-3}" \
  -cp "$OVERRIDE:$JAR" mindustry.desktop.DesktopLauncher $ARGS 2>&1 \
  | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'selftest|\[autobuild\]|\[E\]|Exception|at autobuild'
