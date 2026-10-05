#!/usr/bin/env bash
set -euo pipefail

ROOT="app/src/main/assets/bina"
TMP=".bina-model"
mkdir -p "$ROOT" "$TMP/inference"

BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"

curl -L --fail --retry 3 -o "$TMP/inference/inference.json" "$BASE/inference/inference.json"
curl -L --fail --retry 3 -o "$TMP/inference/inference.pdiparams" "$BASE/inference/inference.pdiparams"
curl -L --fail --retry 3 -o "$ROOT/dict.txt" "$BASE/persian_arabic_bina02_dict.txt"

python3 -m pip install --upgrade pip
python3 -m pip install paddle2onnx onnx

paddle2onnx \
  --model_dir "$TMP/inference" \
  --model_filename inference.json \
  --params_filename inference.pdiparams \
  --save_file "$ROOT/inference.onnx" \
  --opset_version 17 \
  --enable_onnx_checker True

python3 - <<'PY'
import os
p="app/src/main/assets/bina/inference.onnx"
print("Bina ONNX size:", os.path.getsize(p))
assert os.path.getsize(p) > 5_000_000
PY
