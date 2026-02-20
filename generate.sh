#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "Building plugin..."
go build -o protoc-gen-kalam .

echo "Generating..."
rm -rf build && mkdir build
PATH="$PWD:$HOME/.pub-cache/bin:$PATH" \
  protoc --dart_out=build --kalam_out=build \
         --proto_path=testdata \
         testdata/user.proto

echo "Build output:"
ls build/

# Generate into testdata/dart for integration tests
echo ""
echo "Generating for Dart integration tests..."
rm -rf testdata/dart/generated && mkdir -p testdata/dart/generated
PATH="$PWD:$HOME/.pub-cache/bin:$PATH" \
  protoc --dart_out=testdata/dart/generated --kalam_out=testdata/dart/generated \
         --proto_path=testdata \
         testdata/user.proto

echo "Test output:"
ls testdata/dart/generated/
