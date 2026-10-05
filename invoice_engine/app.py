import base64
import io
import os
import re
from typing import Any, Optional

import cv2
import numpy as np
import requests
from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel
from PIL import Image

app = FastAPI(title="FactorKhan Invoice Engine", version="1.0.0")

MISTRAL_API_KEY = os.getenv("MISTRAL_API_KEY", "").strip()
MISTRAL_OCR_MODEL = os.getenv("MISTRAL_OCR_MODEL", "mistral-ocr-latest").strip()
MISTRAL_URL = "https://api.mistral.ai/v1/ocr"


class InvoiceItem(BaseModel):
    description: str = ""
    quantity: float
    unit: str = ""
    unit_price: int
    currency: str = "IRR"
    row_total: int
    confidence: int = 0


class InvoiceResult(BaseModel):
    engine: str
    items: list[InvoiceItem]
    grand_total_irr: int
    grand_total_toman: int
    formatted_total_irr: str
    formatted_total_toman: str
    warning: str = ""


def normalize_digits(value: str) -> str:
    fa = "۰۱۲۳۴۵۶۷۸۹"
    ar = "٠١٢٣٤٥٦٧٨٩"
    out = []
    for ch in str(value):
        if ch in fa:
            out.append(str(fa.index(ch)))
        elif ch in ar:
            out.append(str(ar.index(ch)))
        else:
            out.append(ch)
    return "".join(out)


def parse_number(value: Any) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    s = normalize_digits(str(value)).replace("٬", "").replace("،", "").replace(",", "")
    m = re.search(r"[-+]?\d+(?:\.\d+)?", s)
    if not m:
        return None
    try:
        return float(m.group(0))
    except ValueError:
        return None


def parse_money(value: Any) -> Optional[int]:
    n = parse_number(value)
    if n is None:
        return None
    v = int(round(n))
    return v if v > 0 else None


def normalize_currency(value: Any) -> str:
    s = normalize_digits(str(value or "")).lower()
    if "تومان" in s or "toman" in s or "irt" in s:
        return "TOMAN"
    return "IRR"


def format_money(value: int) -> str:
    return f"{int(value):,}"


def decode_image(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, dtype=np.uint8)
    image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="تصویر قابل خواندن نیست.")
    return image


def deskew(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    inv = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
    coords = np.column_stack(np.where(inv > 0))
    if len(coords) < 100:
        return image
    angle = cv2.minAreaRect(coords[:, ::-1].astype(np.float32))[-1]
    angle = angle - 90 if angle > 45 else angle
    if abs(angle) < 0.25 or abs(angle) > 12:
        return image
    h, w = image.shape[:2]
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(image, matrix, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def enhance(image: np.ndarray) -> np.ndarray:
    image = deskew(image)
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    l = cv2.createCLAHE(clipLimit=2.2, tileGridSize=(8, 8)).apply(l)
    out = cv2.cvtColor(cv2.merge((l, a, b)), cv2.COLOR_LAB2BGR)
    out = cv2.fastNlMeansDenoisingColored(out, None, 4, 4, 7, 21)
    return out


def table_crop(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    bw = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 31, 11
    )
    h, w = bw.shape
    horizontal = cv2.morphologyEx(
        bw,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (max(25, w // 18), 1)),
    )
    vertical = cv2.morphologyEx(
        bw,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(25, h // 25))),
    )
    grid = cv2.add(horizontal, vertical)
    contours, _ = cv2.findContours(grid, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    best = None
    best_area = 0
    for cnt in contours:
        x, y, cw, ch = cv2.boundingRect(cnt)
        area = cw * ch
        if cw > w * 0.45 and ch > h * 0.16 and area > best_area:
            best = (x, y, cw, ch)
            best_area = area
    if not best:
        return image
    x, y, cw, ch = best
    pad_x = int(cw * 0.02)
    pad_y = int(ch * 0.04)
    x0 = max(0, x - pad_x)
    y0 = max(0, y - pad_y)
    x1 = min(w, x + cw + pad_x)
    y1 = min(h, y + ch + pad_y)
    return image[y0:y1, x0:x1]


def image_data_url(image: np.ndarray) -> str:
    ok, buf = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 92])
    if not ok:
        raise HTTPException(status_code=500, detail="خطا در آماده‌سازی تصویر.")
    return "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode("ascii")


def mistral_extract(image: np.ndarray) -> dict[str, Any]:
    if not MISTRAL_API_KEY:
        raise HTTPException(status_code=503, detail="MISTRAL_API_KEY روی سرور تنظیم نشده است.")

    prompt = """
فاکتور فارسی را ردیف به ردیف بخوان. فقط JSON معتبر برگردان.
جمع چاپ‌شده، مبلغ نهایی چاپ‌شده و مبلغ ردیف چاپ‌شده را برای محاسبه استفاده نکن.
برای هر ردیف فقط این فیلدها را بده:
items: [
 {description:string, quantity:number|null, unit:string|null,
  unit_price:number|null, currency:"IRR"|"TOMAN"|null, confidence:number|null}
]
قواعد:
- اعداد فارسی و عربی را به عدد لاتین تبدیل کن.
- quantity باید فقط تعداد باشد؛ مانند "۱۰ شاخه" => quantity=10 و unit="شاخه".
- unit_price فقط قیمت واحد همان ردیف است.
- اگر واحد پول مشخص نبود IRR فرض نکن؛ currency را null بده.
- عدد ناخوانا را null بگذار و حدس نزن.
- ردیف خالی یا سربرگ جدول را آیتم حساب نکن.
- هیچ ضرب یا جمعی انجام نده.
""".strip()

    body = {
        "model": MISTRAL_OCR_MODEL,
        "document": {"type": "image_url", "image_url": image_data_url(image)},
        "include_blocks": True,
        "document_annotation_format": {"type": "json_object"},
        "document_annotation_prompt": prompt,
    }
    try:
        resp = requests.post(
            MISTRAL_URL,
            headers={
                "Authorization": f"Bearer {MISTRAL_API_KEY}",
                "Content-Type": "application/json",
            },
            json=body,
            timeout=120,
        )
    except requests.RequestException as exc:
        raise HTTPException(status_code=502, detail=f"خطای اتصال Mistral: {exc}") from exc

    if not resp.ok:
        raise HTTPException(status_code=502, detail=f"Mistral HTTP {resp.status_code}: {resp.text[:300]}")

    root = resp.json()
    ann = root.get("document_annotation") or {}
    if isinstance(ann, str):
        import json
        try:
            ann = json.loads(ann)
        except Exception:
            ann = {}
    return ann if isinstance(ann, dict) else {}


def build_items(annotation: dict[str, Any]) -> list[InvoiceItem]:
    items: list[InvoiceItem] = []
    for raw in annotation.get("items") or []:
        if not isinstance(raw, dict):
            continue
        qty = parse_number(raw.get("quantity"))
        price = parse_money(raw.get("unit_price"))
        if qty is None or price is None or qty <= 0 or price <= 0:
            continue
        confidence_raw = parse_number(raw.get("confidence"))
        confidence = 0
        if confidence_raw is not None:
            confidence = int(round(confidence_raw * 100 if confidence_raw <= 1 else confidence_raw))
            confidence = max(0, min(100, confidence))
        currency = normalize_currency(raw.get("currency"))
        row_total = int(round(qty * price))
        items.append(
            InvoiceItem(
                description=str(raw.get("description") or "").strip(),
                quantity=qty,
                unit=str(raw.get("unit") or "").strip(),
                unit_price=price,
                currency=currency,
                row_total=row_total,
                confidence=confidence,
            )
        )
    return items


def score(items: list[InvoiceItem]) -> float:
    if not items:
        return 0.0
    conf = sum(x.confidence for x in items) / len(items)
    return len(items) * 100.0 + conf


def compute_result(items: list[InvoiceItem]) -> InvoiceResult:
    total_irr = 0
    low_conf = 0
    for item in items:
        if item.confidence and item.confidence < 55:
            low_conf += 1
        value = item.row_total * (10 if item.currency == "TOMAN" else 1)
        total_irr += value

    warning = ""
    if not items:
        warning = "ردیف قابل محاسبه‌ای پیدا نشد."
    elif low_conf:
        warning = f"{low_conf} ردیف اطمینان پایین دارد و بهتر است کنترل شود."

    return InvoiceResult(
        engine="python-opencv-mistral",
        items=items,
        grand_total_irr=total_irr,
        grand_total_toman=total_irr // 10,
        formatted_total_irr=format_money(total_irr),
        formatted_total_toman=format_money(total_irr // 10),
        warning=warning,
    )


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "engine": "python-opencv-mistral",
        "mistral_configured": bool(MISTRAL_API_KEY),
        "model": MISTRAL_OCR_MODEL,
    }


@app.post("/analyze", response_model=InvoiceResult)
async def analyze(file: UploadFile = File(...)) -> InvoiceResult:
    data = await file.read()
    if not data or len(data) > 12 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="حجم تصویر نامعتبر است.")

    original = decode_image(data)
    enhanced = enhance(original)
    first = build_items(mistral_extract(table_crop(enhanced)))

    # اگر استخراج اولیه ضعیف بود، یک پاس دوم روی کل تصویر اصلاح‌شده انجام می‌شود.
    avg_conf = (sum(i.confidence for i in first) / len(first)) if first else 0
    if len(first) < 1 or avg_conf < 55:
        second = build_items(mistral_extract(enhanced))
        items = second if score(second) > score(first) else first
    else:
        items = first

    return compute_result(items)
