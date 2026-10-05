#!/usr/bin/env bash
set -euo pipefail
TMP=".bina-model"
mkdir -p "$TMP/inference"
BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"
curl -L --fail --retry 3 -o "$TMP/inference/inference.json" "$BASE/inference/inference.json"
python3 - <<'PY'
import json
obj=json.load(open(".bina-model/inference/inference.json"))
s=json.dumps(obj, ensure_ascii=False)
print("LEN", len(s))
for pat in ['"shape"', '"dims"', '"x"', '"data"']:
    print("PAT", pat)
    start=0
    for i in range(12):
        p=s.find(pat,start)
        if p<0: break
        print(s[max(0,p-500):p+1200])
        start=p+1
PY
exit 9
