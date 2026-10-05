#!/usr/bin/env bash
set -euo pipefail

ROOT="app/src/main/assets/bina"
WORK=".bina-build"
PPOCR="$WORK/PaddleOCR"
mkdir -p "$ROOT" "$WORK"

BASE="https://huggingface.co/Reza2kn/Bina-0.2-RizehPizeh/resolve/main"
curl -L --fail --retry 3 -o "$WORK/config.yml" "$BASE/config.yml"
curl -L --fail --retry 3 -o "$WORK/Bina-0.2-RizehPizeh.pdparams" "$BASE/Bina-0.2-RizehPizeh.pdparams"
curl -L --fail --retry 3 -o "$ROOT/dict.txt" "$BASE/persian_arabic_bina02_dict.txt"

git clone --depth 1 https://github.com/PaddlePaddle/PaddleOCR.git "$PPOCR"
cd "$PPOCR"
git fetch --depth 1 origin 2661c7c0ef5c613e8f93c6e93b2e052399f0f854
git checkout 2661c7c0ef5c613e8f93c6e93b2e052399f0f854
cd - >/dev/null

python3 -m pip install --upgrade pip
python3 -m pip install "paddlepaddle==3.3.1" "paddle2onnx==2.1.0" "onnx==1.17.0" pyyaml
python3 -m pip install -r "$PPOCR/requirements.txt"

PYTHONPATH="$PPOCR:${PYTHONPATH:-}" python3 - <<'PY'
import os, sys, yaml, paddle
sys.path.insert(0, ".bina-build/PaddleOCR")

from ppocr.modeling.architectures import build_model
from ppocr.postprocess import build_post_process

cfg=yaml.safe_load(open(".bina-build/config.yml", encoding="utf-8"))
cfg["Global"]["use_gpu"]=False
cfg["Global"]["pretrained_model"]=".bina-build/Bina-0.2-RizehPizeh"
cfg["Global"]["character_dict_path"]="app/src/main/assets/bina/dict.txt"

post=build_post_process(cfg["PostProcess"], cfg["Global"])
char_num=len(post.character)
arch=cfg["Architecture"]

# Match PaddleOCR's MultiHead channel setup.
if arch.get("Head",{}).get("name")=="MultiHead":
    out_channels_list={}
    for head in arch["Head"]["head_list"]:
        head_name=list(head.keys())[0]
        if head_name=="CTCHead":
            out_channels_list["CTCLabelDecode"]=char_num
        elif head_name=="SARHead":
            out_channels_list["SARLabelDecode"]=char_num+2
        elif head_name=="NRTRHead":
            out_channels_list["NRTRLabelDecode"]=char_num+3
    arch["Head"]["out_channels_list"]=out_channels_list
else:
    arch["Head"]["out_channels"]=char_num

model=build_model(arch)
state=paddle.load(".bina-build/Bina-0.2-RizehPizeh.pdparams")
missing=[]
try:
    model.set_state_dict(state)
except Exception as e:
    print("set_state_dict error",e)
    raise
model.eval()

spec=[paddle.static.InputSpec(shape=[1,3,48,768], dtype="float32", name="x")]
static_model=paddle.jit.to_static(model, input_spec=spec, full_graph=True)
os.makedirs(".bina-build/static", exist_ok=True)
paddle.jit.save(static_model, ".bina-build/static/inference", input_spec=spec)
print("STATIC_FILES", os.listdir(".bina-build/static"))
PY

MODEL_FILE="inference.json"
if [ ! -f "$WORK/static/$MODEL_FILE" ]; then MODEL_FILE="inference.pdmodel"; fi
PARAM_FILE="inference.pdiparams"

paddle2onnx \
  --model_dir "$WORK/static" \
  --model_filename "$MODEL_FILE" \
  --params_filename "$PARAM_FILE" \
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
