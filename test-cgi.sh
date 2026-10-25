#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cgi_test_dir=$(mktemp -d)
trap 'rm -rf "$cgi_test_dir"' EXIT
mkdir "$cgi_test_dir/classes" "$cgi_test_dir/tmp"
javac -d "$cgi_test_dir/classes" src/*.java src/utils/*.java tests/CGIHandlerTest.java
java -Djava.io.tmpdir="$cgi_test_dir/tmp" -cp "$cgi_test_dir/classes" CGIHandlerTest
