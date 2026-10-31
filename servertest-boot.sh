#!/bin/bash
# End-to-end check of the bootstrap layout on a dedicated server: host a map (survival, waves off),
# plan a base around the core, place it instantly, then measure the items per second the core gains
# against the forecast.
#   ./servertest-boot.sh <map> [abboot options]     (WARMUP=seconds, MEASURE=seconds)
cd ~/mindustry-test
JAVA="$HOME/jdk17/bin/java"; [ -x "$JAVA" ] || JAVA=java # else JDK 17 on the PATH
# GNU timeout (Linux), else perl's alarm (macOS has no timeout)
limit(){ if command -v timeout >/dev/null; then timeout "$@"; else perl -e 'alarm shift; exec @ARGV' "$@"; fi; }
map=$1; shift
{
  sleep 4; echo "host $map survival"
  sleep 10; echo "abboot $*"  # big maps take a while to load
  sleep ${WARMUP:-40}; echo "abbootcount"   # warm-up: belts fill, modules start
  sleep ${MEASURE:-60}; echo "abbootcount"  # steady state
  sleep ${MEASURE:-60}; echo "abbootcount"
  sleep 1; echo "exit"
} | limit $((30 + ${WARMUP:-40} + 2 * ${MEASURE:-60})) "$JAVA" -jar server.jar 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '\[ab|autobuild|\[E\]|Exception|at mindustry|at autobuild' | sed 's/^\[[^]]*\] //'
