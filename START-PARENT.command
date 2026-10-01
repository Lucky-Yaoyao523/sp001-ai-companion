#!/bin/sh
cd "$(dirname "$0")" || exit 1
exec node parent-console/server.mjs
