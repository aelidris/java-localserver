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

# 4. Test POST /uploads/... (Expected: 201 Created - Upload Flow)
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$SERVER_URL/uploads/automated-test.txt" -d "Automated curl test content")
if [ "$status" -eq 201 ]; then 
    echo "✅ [PASS] POST /uploads/automated-test.txt -> Status: 201 Created" 
else 
    echo "❌ [FAIL] POST /uploads/automated-test.txt -> Expected 201, got $status" 
fi

# 5. Test DELETE /uploads/... (Expected: 200 OK - Delete Flow)
status=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$SERVER_URL/uploads/automated-test.txt")
if [ "$status" -eq 200 ] || [ "$status" -eq 204 ]; then 
    echo "✅ [PASS] DELETE /uploads/automated-test.txt -> Status: 200/204 OK" 
else 
    echo "❌ [FAIL] DELETE /uploads/automated-test.txt -> Expected 200/204, got $status" 
fi

# 6. Test 400 Bad Request (Using nc to send malformed request)
response=$(printf "BAD_HTTP_REQUEST_FORMAT\r\n\r\n" | nc $HOST $PORT)
if echo "$response" | grep -q "400 Bad Request"; then
    echo "✅ [PASS] 400 Bad Request -> Detected successfully"
else
    echo "❌ [FAIL] 400 Bad Request -> Not detected"
fi

echo "🧪 ========================================="
echo "🏁 All available tests completed!"