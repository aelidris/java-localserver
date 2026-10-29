#!/usr/bin/env python3
import os
import sys

# add sys.exit(1) if you want to check internal server error 500
# sys.exit(1)


# Send HTTP headers including Set-Cookie
print("Content-Type: text/plain")
print()  # Empty line separates headers from body

print("=== Python CGI Test Success ===")
print("Method:", os.environ.get("REQUEST_METHOD", "N/A"))
print("Query String:", os.environ.get("QUERY_STRING", "N/A"))
print("Session ID:", os.environ.get("SESSION_ID", "N/A"))
print("Session visits:", os.environ.get("SESSION_VISITS", "N/A"))

if os.environ.get("REQUEST_METHOD") == "POST":
    body = sys.stdin.read()
    print("POST Body:", body)
