#!/usr/bin/env bash

set -eo pipefail

sudo apt-get update && sudo apt-get install -y \
  libpcap-dev patchelf \
  libgtk-3-dev libwebkit2gtk-4.0-dev

env | sort

cd apps/desktop && go build -ldflags "-s -w -X main.version=$GITHUB_REF_NAME" -o ../../albiondata-client . && cd ../..

patchelf --replace-needed libpcap.so.0.8 libpcap.so albiondata-client

cp albiondata-client albiondata-client.old
gzip -9 albiondata-client
mv albiondata-client.gz update-linux-amd64.gz
mv albiondata-client.old albiondata-client
