#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/src/main/assets/tessdata
curl -L --fail --retry 3 -o app/src/main/assets/tessdata/fas.traineddata https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/fas.traineddata
curl -L --fail --retry 3 -o app/src/main/assets/tessdata/eng.traineddata https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata
