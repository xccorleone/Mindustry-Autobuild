#!/bin/sh
# Offline tests (no game needed). `./test.sh` runs everything: drill and defense planners on random
# maps with invariant checks (prints two ASCII layouts, a few minutes), then the bootstrap domain.
# `./test.sh bootstrap` runs only the bootstrap domain tests (seconds).
set -e
BIN="$HOME/jdk17/bin/"
[ -x "${BIN}javac" ] || BIN="" # else JDK 17 on the PATH
if grep -rlE "^import (static )?(mindustry|arc)\." src/autobuild/bootstrap/domain src/autobuild/bootstrap/app; then
    echo "the bootstrap domain and use cases must not import game classes"
    exit 1
fi
OUT=$(mktemp -d)
"${BIN}javac" -d "$OUT" src/autobuild/plan/*.java $(find src/autobuild/bootstrap/domain src/autobuild/bootstrap/app -name '*.java') test/autobuild/*.java test/autobuild/bootstrap/*.java
if [ "$1" != bootstrap ]; then
    "${BIN}java" -cp "$OUT" autobuild.DefenseTest
    "${BIN}java" -cp "$OUT" autobuild.PlannerTest
fi
"${BIN}java" -cp "$OUT" autobuild.bootstrap.BootstrapTest
