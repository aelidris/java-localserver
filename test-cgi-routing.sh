#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cgi_routing_dir=$(mktemp -d)
trap 'rm -rf "$cgi_routing_dir"' EXIT
javac -d "$cgi_routing_dir" src/*.java src/utils/*.java tests/CGIRoutingTest.java
java -cp "$cgi_routing_dir" CGIRoutingTest
