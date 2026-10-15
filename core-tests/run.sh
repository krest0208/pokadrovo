#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .core-test-classes
javac -encoding UTF-8 -d .core-test-classes \
 app/src/main/java/ru/pokadrovo/app/Json.java \
 app/src/main/java/ru/pokadrovo/app/EditProject.java \
 app/src/main/java/ru/pokadrovo/app/CaptionTools.java \
 core-tests/CoreTests.java
java -cp .core-test-classes CoreTests
