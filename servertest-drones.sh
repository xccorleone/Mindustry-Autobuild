#!/bin/bash
# End-to-end check of the safe drones on a dedicated server: host a map (survival: in sandbox
# builders reach any distance), put an enemy
# anti-air turret far from the core with jobs at its range's edge and deep inside, a poly (rebuild)
# and a mega (repair) outside, then report how close they came and which jobs got done.
#   ./servertest-drones.sh <map> [off] [inside]     (off = vanilla AI, to compare; inside = the drones
#   start inside the range; WATCH=seconds)
cd ~/mindustry-test
JAVA="$HOME/jdk17/bin/java"; [ -x "$JAVA" ] || JAVA=java # else JDK 17 on the PATH
# GNU timeout (Linux), else perl's alarm (macOS has no timeout)
limit(){ if command -v timeout >/dev/null; then timeout "$@"; else perl -e 'alarm shift; exec @ARGV' "$@"; fi; }
map=$1; shift
{
  sleep 4; echo "host $map survival"
  sleep 6; echo "abdrones $*"
  sleep ${WATCH:-40}; echo "abdronescount"
  sleep ${WATCH:-40}; echo "abdronescount"
  sleep 1; echo "exit"
} | limit $((30 + 2 * ${WATCH:-40})) "$JAVA" -jar server.jar 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '\[ab|autobuild|\[E\]|Exception|at mindustry|at autobuild' | sed 's/^\[[^]]*\] //'
