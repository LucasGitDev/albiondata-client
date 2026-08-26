#!/usr/bin/env bash

set -eo pipefail

rm -f apps/desktop/rsrc_windows_*
rm -f apps/desktop/albiondata-client.exe
rm -f apps/desktop/albiondata-client-amd64-installer.exe
rm -f albiondata-client.exe
rm -f albiondata-client.*.bak
rm -f .albiondata-client.*.old
rm -f albiondata-client-amd64-installer.exe

go install github.com/tc-hib/go-winres@v0.3.1

export PATH="$PATH:/root/go/bin"

cd apps/desktop

# Generate Windows resources (.syso) — reads winres/winres.json from CWD
go-winres make

# Cross-compile for Windows (requires mingw-w64)
env GOOS=windows GOARCH=amd64 CGO_ENABLED=1 CC=x86_64-w64-mingw32-gcc \
  go build -ldflags "-s -w -X main.version=$GITHUB_REF_NAME" -o albiondata-client.exe -v .

# Patch the exe with version info (must run from same dir as winres/)
go-winres patch albiondata-client.exe

# Copy repo-root LICENSE here so NSIS (TOP_SRCDIR=../.. = apps/desktop) can find it
cp ../../LICENSE LICENSE

cd pkg/nsis
make nsis

# NSIS outputs installer to apps/desktop/ (../../ from pkg/nsis)
cd ../../..

# Copy artifacts to repo root
cp apps/desktop/albiondata-client.exe albiondata-client.exe
cp apps/desktop/albiondata-client-amd64-installer.exe albiondata-client-amd64-installer.exe

ls -la albiondata-client*

cp albiondata-client.exe albiondata-client.exe.copy
gzip -9 albiondata-client.exe
mv albiondata-client.exe.gz update-windows-amd64.exe.gz
mv albiondata-client.exe.copy albiondata-client.exe
