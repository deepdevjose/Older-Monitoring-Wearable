#!/usr/bin/env bash
# Run the already installed local environment; never downloads dependencies.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$task_root/.venv/bin/python" "$task_root/tools/start_offline.py" "$@"
