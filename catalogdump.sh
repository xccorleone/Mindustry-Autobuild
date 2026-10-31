#!/bin/sh
# Regenerates the bootstrap tests' game data (test/autobuild/bootstrap/fixtures/catalog-v160.7.txt)
# from the Mindustry jar in the Gradle cache (run ./gradlew jar once to download it).
set -e
BIN="$HOME/jdk17/bin/"
[ -x "${BIN}javac" ] || BIN=""
JAR=$(ls ~/.gradle/caches/modules-2/files-2.1/Anuken/Mindustry/v160.7/*/Mindustry-v160.7.jar | head -1)
OUT=$(mktemp -d)
# -sourcepath: the jar also holds the game's sources, which must not be compiled
"${BIN}javac" -sourcepath src -cp "$JAR" -d "$OUT" src/autobuild/bootstrap/infra/CatalogDump.java
"${BIN}java" -cp "$JAR:$OUT" autobuild.bootstrap.infra.CatalogDump test/autobuild/bootstrap/fixtures/catalog-v160.7.txt > /dev/null
