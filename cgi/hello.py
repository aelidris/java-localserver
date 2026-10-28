import json
import os
import sys

body = sys.stdin.buffer.read()
response = json.dumps({
    "message": "Hello from CGI",
    "method": os.environ.get("REQUEST_METHOD", ""),
    "path_info": os.environ.get("PATH_INFO", ""),
    "query_string": os.environ.get("QUERY_STRING", ""),
    "body_bytes": len(body),
}).encode("utf-8")
sys.stdout.buffer.write(b"Content-Type: application/json; charset=UTF-8\r\n\r\n" + response)
