#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" src/*.java src/utils/*.java tests/*.java
for test in ConfigLoaderTest HttpResponseWriterTest RouterTest RedirectTest DirectoryListingTest ChunkedRequestTest SessionTest; do
    java -cp "$classes" "$test"
done
python3 test-resilience.py
python3 test-cgi.py
python3 test-requirements.py
