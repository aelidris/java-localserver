#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
request_test_dir=$(mktemp -d)
trap 'rm -rf "$request_test_dir"' EXIT
javac -d "$request_test_dir" src/*.java src/utils/*.java tests/HttpRequestReaderTest.java tests/ChunkedRequestTest.java
java -cp "$request_test_dir" HttpRequestReaderTest
java -cp "$request_test_dir" ChunkedRequestTest
