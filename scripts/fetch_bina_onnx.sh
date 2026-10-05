#!/usr/bin/env bash
set -euo pipefail
BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"
curl -L --fail --retry 3 -o /tmp/config.yml "$BASE/config.yml"
curl -L --fail --retry 3 -o /tmp/commit "$BASE/PADDLEOCR_COMMIT"
echo "PADDLE_COMMIT=$(cat /tmp/commit)"
echo "===CONFIG==="
cat /tmp/config.yml
exit 9
