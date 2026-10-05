#!/usr/bin/env bash
set -euo pipefail

ROOT="app/src/main/assets/bina"
TMP=".bina-model"
FIXED=".bina-fixed"
mkdir -p "$ROOT" "$TMP/inference" "$FIXED"

BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"
curl -L --fail --retry 3 -o "$TMP/inference/inference.json" "$BASE/inference/inference.json"
curl -L --fail --retry 3 -o "$TMP/inference/inference.pdiparams" "$BASE/inference/inference.pdiparams"
curl -L --fail --retry 3 -o "$ROOT/dict.txt" "$BASE/persian_arabic_bina02_dict.txt"

python3 -m pip install --upgrade pip
python3 -m pip install "paddlepaddle==3.3.1" "paddle2onnx==2.1.0" "onnx==1.17.0"

python3 - <<'PY'
import paddle, os
paddle.enable_static()
exe = paddle.static.Executor(paddle.CPUPlace())
model_dir = ".bina-model/inference"
program, feed_names, fetch_targets = paddle.static.load_inference_model(
    model_dir,
    exe,
    model_filename="inference.json",
    params_filename="inference.pdiparams",
)
print("feeds", feed_names)
block = program.global_block()
for name in feed_names:
    v = block.var(name)
    print("old shape", name, v.shape)
    v.desc.set_shape([1,3,48,768])
for op in block.ops:
    try:
        op.desc.infer_shape(block.desc)
    except Exception as e:
        print("infer_shape warning", op.type, e)
feed_vars = [block.var(n) for n in feed_names]
os.makedirs(".bina-fixed", exist_ok=True)
paddle.static.save_inference_model(
    path_prefix=".bina-fixed/inference",
    feed_vars=feed_vars,
    fetch_vars=fetch_targets,
    executor=exe,
    program=program,
)
print("saved", os.listdir(".bina-fixed"))
PY

MODEL_FILE="inference.json"
if [ ! -f "$FIXED/$MODEL_FILE" ]; then
  MODEL_FILE="inference.pdmodel"
fi

paddle2onnx \
  --model_dir "$FIXED" \
  --model_filename "$MODEL_FILE" \
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
assert os.path.getsize(p) > 5_000_000
PY
