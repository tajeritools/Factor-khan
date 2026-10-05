#!/usr/bin/env bash
set -euo pipefail

TMP=".khattat-model"
mkdir -p "$TMP"

BASE="https://huggingface.co/saeidseyfi/khattat-crnn/resolve/main"
curl -L --fail --retry 3 -o "$TMP/best.pt" "$BASE/best.pt"
curl -L --fail --retry 3 -o "$TMP/vocab.json" "$BASE/vocab.json"

python3 - <<'PY'
import torch, json
ck=torch.load(".khattat-model/best.pt", map_location="cpu", weights_only=False)
print("CHECKPOINT_KEYS", list(ck.keys()))
state=ck.get("model_state") or ck.get("model") or ck.get("state_dict")
print("STATE_LEN", len(state))
for k,v in state.items():
    print("PARAM", k, tuple(v.shape))
print("VOCAB_CHARS", ck.get("vocab_chars"))
print("VOCAB_JSON", open(".khattat-model/vocab.json", encoding="utf-8").read()[:4000])
PY

exit 9
