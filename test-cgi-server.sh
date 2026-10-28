#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cgi_server_dir=$(mktemp -d)
trap 'rm -rf "$cgi_server_dir"' EXIT
javac -d "$cgi_server_dir" src/*.java src/utils/*.java tests/CGIServerTest.java
java -cp "$cgi_server_dir" CGIServerTest
