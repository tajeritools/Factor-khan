#!/usr/bin/env bash
set -euo pipefail

ROOT="app/src/main/assets/bina"
TMP=".bina-model"
mkdir -p "$ROOT" "$TMP/inference"

BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"
curl -L --fail --retry 3 -o "$TMP/inference/inference.json" "$BASE/inference/inference.json"
curl -L --fail --retry 3 -o "$TMP/inference/inference.pdiparams" "$BASE/inference/inference.pdiparams"
curl -L --fail --retry 3 -o "$ROOT/dict.txt" "$BASE/persian_arabic_bina02_dict.txt"

python3 - <<'PY'
import json
p=".bina-model/inference/inference.json"
obj=json.load(open(p))

changed=0
def walk(x):
    global changed
    if isinstance(x,dict):
        # Fix only the data op named x and its direct output tensor.
        if x.get("#")=="1.data":
            attrs=x.get("A",[])
            is_x=False
            for a in attrs:
                if a.get("N")=="name" and a.get("AT",{}).get("D")=="x":
                    is_x=True
            if is_x:
                for a in attrs:
                    if a.get("N")=="shape" and a.get("AT",{}).get("D")==[-1,3,48,-1]:
                        a["AT"]["D"]=[1,3,48,768]
                        changed+=1
                for o in x.get("O",[]):
                    tt=o.get("TT",{})
                    d=tt.get("D")
                    if isinstance(d,list) and len(d)>1 and d[1]==[-1,3,48,-1]:
                        d[1]=[1,3,48,768]
                        changed+=1
        for v in x.values(): walk(v)
    elif isinstance(x,list):
        for v in x: walk(v)

walk(obj)
print("patched",changed)
assert changed>=2
json.dump(obj,open(p,"w"),ensure_ascii=False,separators=(",",":"))
PY

python3 -m pip install --upgrade pip
python3 -m pip install "paddlepaddle==3.3.1" "paddle2onnx==2.1.0" "onnx==1.17.0"

paddle2onnx \
  --model_dir "$TMP/inference" \
  --model_filename inference.json \
  --params_filename inference.pdiparams \
  --save_file "$ROOT/inference.onnx" \
  --opset_version 17 \
  --enable_onnx_checker True

python3 - <<'PY'
import os, onnx
p="app/src/main/assets/bina/inference.onnx"
print("Bina ONNX size:", os.path.getsize(p))
m=onnx.load(p)
print("input:", [(x.name,[d.dim_value for d in x.type.tensor_type.shape.dim]) for x in m.graph.input])
print("output:", [(x.name,[d.dim_value for d in x.type.tensor_type.shape.dim]) for x in m.graph.output])
assert os.path.getsize(p)>5_000_000
PY
