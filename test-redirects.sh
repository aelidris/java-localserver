#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
redirect_test_dir=$(mktemp -d)
trap 'rm -rf "$redirect_test_dir"' EXIT
javac -d "$redirect_test_dir" src/*.java src/utils/*.java tests/RedirectTest.java
java -cp "$redirect_test_dir" RedirectTest
