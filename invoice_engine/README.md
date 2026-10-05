# FactorKhan Python Invoice Engine

موتور اصلی کنترل سریع فاکتور:
- OpenCV برای اصلاح تصویر، deskew، بهبود کنتراست و پیدا کردن جدول
- Mistral OCR برای استخراج ساختاری شرح، تعداد، واحد و قیمت واحد
- محاسبه قطعی ردیف‌ها و جمع نهایی در Python
- جمع چاپ‌شده روی فاکتور در محاسبات استفاده نمی‌شود

## اجرا

```bash
export MISTRAL_API_KEY=...
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8080
```

سلامت سرویس: `GET /health`
تحلیل فاکتور: `POST /analyze` با multipart field به نام `file`.
