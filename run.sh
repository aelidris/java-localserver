#!/bin/bash

# Exit immediately if any command fails
set -e

echo "🔨 Creating output directory..."
mkdir -p out

echo "📦 Compiling Java source files..."
javac -d out src/*.java src/utils/*.java

echo "🚀 Compiling complete. Starting Server (Main)..."
java -cp out Main "$@"
