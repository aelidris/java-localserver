#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
routing_test_dir=$(mktemp -d)
trap 'rm -rf "$routing_test_dir"' EXIT
javac -d "$routing_test_dir" src/*.java src/utils/*.java tests/RouterTest.java
java -cp "$routing_test_dir" RouterTest
