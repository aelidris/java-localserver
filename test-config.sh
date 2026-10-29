#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" src/*.java src/utils/*.java tests/ConfigLoaderTest.java
java -cp "$test_classes" ConfigLoaderTest
