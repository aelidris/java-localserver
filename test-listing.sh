#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
listing_test_dir=$(mktemp -d)
trap 'rm -rf "$listing_test_dir"' EXIT
javac -d "$listing_test_dir" src/*.java src/utils/*.java tests/DirectoryListingTest.java
java -cp "$listing_test_dir" DirectoryListingTest
