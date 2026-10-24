#!/bin/bash

echo "🧪 ========================================="
echo "🧪 Starting HTTP Server Automated Tests..."
echo "🧪 ========================================="

SERVER_URL="http://localhost:8080"
HOST="127.0.0.1"
PORT="8080"

# 1. Test GET / (Expected: 200 OK)
status=$(curl -s -o /dev/null -w "%{http_code}" "$SERVER_URL/")
if [ "$status" -eq 200 ]; then 
    echo "✅ [PASS] GET / -> Status: 200 OK" 
else 
    echo "❌ [FAIL] GET / -> Expected 200, got $status" 
fi

# 2. Test GET /not-found-page (Expected: 404 Not Found)
status=$(curl -s -o /dev/null -w "%{http_code}" "$SERVER_URL/not-found-page")
if [ "$status" -eq 404 ]; then 
    echo "✅ [PASS] GET /not-found-page -> Status: 404 Not Found" 
else 
    echo "❌ [FAIL] GET /not-found-page -> Expected 404, got $status" 
fi

# 3. Test GET /images (Directory - Expected: 403 Forbidden)
status=$(curl -s -o /dev/null -w "%{http_code}" "$SERVER_URL/images")
if [ "$status" -eq 403 ]; then 
    echo "✅ [PASS] GET /images (Directory) -> Status: 403 Forbidden" 
else 
    echo "❌ [FAIL] GET /images (Directory) -> Expected 403, got $status" 
fi

# 4. Test POST / (Expected: 405 Method Not Allowed)
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$SERVER_URL/")
if [ "$status" -eq 405 ]; then 
    echo "✅ [PASS] POST / -> Status: 405 Method Not Allowed" 
else 
    echo "❌ [FAIL] POST / -> Expected 405, got $status" 
fi

# 5. Test 400 Bad Request (Using nc to send malformed request)
response=$(printf "BAD_HTTP_REQUEST_FORMAT\r\n\r\n" | nc $HOST $PORT)
if echo "$response" | grep -q "400 Bad Request"; then
    echo "✅ [PASS] 400 Bad Request -> Detected successfully"
else
    echo "❌ [FAIL] 400 Bad Request -> Not detected"
fi

# 6. Test 413 Payload Too Large (Placeholder - tested after POST implementation)
echo "ℹ️ [INFO] 413 Payload Too Large test will be active after POST implementation."

# 7. Test 500 Internal Server Error (Placeholder)
echo "ℹ️ [INFO] 500 Internal Server Error test is ready for server-side exceptions."

echo "🧪 ========================================="
echo "🏁 All available tests completed!"