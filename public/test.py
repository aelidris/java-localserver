#!/usr/bin/env python3
import os
import sys

# Send HTTP headers including Set-Cookie
print("Content-Type: text/plain")
print("Set-Cookie: SESSION_ID=audit_token_12345; Path=/")
print()  # Empty line separates headers from body

print("=== Python CGI Test Success ===")
print("Method:", os.environ.get("REQUEST_METHOD", "N/A"))
print("Query String:", os.environ.get("QUERY_STRING", "N/A"))

if os.environ.get("REQUEST_METHOD") == "POST":
    body = sys.stdin.read()
    print("POST Body:", body)