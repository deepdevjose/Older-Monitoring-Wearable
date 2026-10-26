#!/usr/bin/env bash
# Compatibility launcher; carectl is the only server command implementation.
set -euo pipefail
exec "$(dirname "$0")/carectl" serve "$@"
