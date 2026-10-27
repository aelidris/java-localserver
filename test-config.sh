#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
config_test_dir=$(mktemp -d)
trap 'rm -rf "$config_test_dir"' EXIT
javac -d "$config_test_dir" src/*.java src/utils/*.java tests/ConfigLoaderTest.java
java -cp "$config_test_dir" ConfigLoaderTest
