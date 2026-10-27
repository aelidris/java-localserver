#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
response_test_dir=$(mktemp -d)
trap 'rm -rf "$response_test_dir"' EXIT
javac -d "$response_test_dir" src/*.java src/utils/*.java tests/HttpResponseWriterTest.java
java -cp "$response_test_dir" HttpResponseWriterTest
