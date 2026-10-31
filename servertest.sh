#!/bin/bash
# End-to-end check on a dedicated server (no GUI): host a map, place a planned drill field
# instantly, then measure items/s arriving at the port.
#   ./servertest.sh <map> <item> <drill> <dir> <mode> [size] [belt]
cd ~/mindustry-test
JAVA="$HOME/jdk17/bin/java"; [ -x "$JAVA" ] || JAVA=java # else JDK 17 on the PATH
# GNU timeout (Linux), else perl's alarm (macOS has no timeout)
limit(){ if command -v timeout >/dev/null; then timeout "$@"; else perl -e 'alarm shift; exec @ARGV' "$@"; fi; }
map=$1; shift
{
  sleep 4; echo "host $map sandbox"
  sleep 5; echo "abtest $*"
  sleep 15; echo "abcount"   # warm-up: drills spin up, belts fill
  sleep ${MEASURE:-30}; echo "abcount"   # steady state
  sleep ${MEASURE:-30}; echo "abcount"
  sleep 1; echo "exit"
} | limit $((60 + 2 * ${MEASURE:-30})) "$JAVA" -jar server.jar 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '\[ab|autobuild|\[E\]|Exception|at mindustry|at autobuild' | sed 's/^\[[^]]*\] //'
