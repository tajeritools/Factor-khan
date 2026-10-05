#!/usr/bin/env bash
set -euo pipefail
TMP=".bina-model"
mkdir -p "$TMP/inference"
BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"
curl -L --fail --retry 3 -o "$TMP/inference/inference.json" "$BASE/inference/inference.json"
python3 - <<'PY'
import json
p=".bina-model/inference/inference.json"
obj=json.load(open(p))
print("TOP_KEYS", list(obj.keys()))
def walk(x,path="root"):
    if isinstance(x,dict):
        s=str(x)
        if '"x"' in s and ("shape" in s.lower() or "feed" in s.lower()):
            print("MATCH", path, s[:3000])
        for k,v in x.items():
            walk(v,path+"."+str(k))
    elif isinstance(x,list):
        for i,v in enumerate(x):
            walk(v,path+f"[{i}]")
walk(obj)
PY
exit 9
