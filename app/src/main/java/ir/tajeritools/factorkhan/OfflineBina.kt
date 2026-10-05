package ir.tajeritools.factorkhan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class OfflineHandwritingResult(
    val text: String,
    val items: List<InvoiceItem>,
    val confidence: Int,
    val lineCount: Int
)

class OfflineBinaEngine(private val context: Context) {
    private val modelDir = File(context.filesDir, "bina_model")
    private val modelFile = File(modelDir, "inference.onnx")
    private val dictFile = File(modelDir, "dict.txt")

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 5_000_000 && dictFile.exists()

    fun ensureModel() {
        modelDir.mkdirs()
        if (!modelFile.exists() || modelFile.length() < 5_000_000) {
            context.assets.open("bina/inference.onnx").use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
        }
        if (!dictFile.exists() || dictFile.length() < 50) {
            context.assets.open("bina/dict.txt").use { input ->
                FileOutputStream(dictFile).use { output -> input.copyTo(output) }
            }
        }
    }

    fun recognizeInvoice(imageFile: File): OfflineHandwritingResult {
        ensureModel()
        val dictionary = dictFile.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        require(dictionary.isNotEmpty()) { "فرهنگ نویسه‌های بینا پیدا نشد." }

        val bitmap = loadAndNormalizeOrientation(imageFile)
        val lineCrops = segmentLines(bitmap)

        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val recognized = mutableListOf<Pair<String, Int>>()

        env.createSession(modelFile.absolutePath, sessionOptions).use { session ->
            val inputName = session.inputNames.first()

            lineCrops.forEach { crop ->
                val tensorData = prepareInput(crop)
                val shape = longArrayOf(1, 3, 48, 768)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { output ->
                        val first = output[0]
                        val value = first.value
                        val decoded = decodeCtc(value, dictionary)
                        if (decoded.first.isNotBlank()) recognized += decoded
                    }
                }
                crop.recycle()
            }
        }

        bitmap.recycle()

        val text = recognized.joinToString("\n") { it.first }
        val confidence = if (recognized.isEmpty()) 0 else recognized.map { it.second }.average().toInt()
        val items = InvoiceAnalyzer.extractItems(text)

        return OfflineHandwritingResult(
            text = text,
            items = items,
            confidence = confidence,
            lineCount = recognized.size
        )
    }

    private fun loadAndNormalizeOrientation(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 3200) sample *= 2

        val original = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("تصویر قابل خواندن نیست.")

        val rotation = runCatching {
            when (ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (rotation == 0f) return original
        val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            .also { original.recycle() }
    }

    private fun segmentLines(source: Bitmap): List<Bitmap> {
        val maxW = 1800
        val scaled = if (source.width > maxW) {
            val h = (source.height * (maxW.toFloat() / source.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, maxW, h, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val darkness = IntArray(h)
        for (y in 0 until h) {
            var count = 0
            val base = y * w
            for (x in 0 until w) {
                val c = pixels[base + x]
                val gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
                if (gray < 185) count++
            }
            darkness[y] = count
        }

        val activeThreshold = max(4, (w * 0.006).toInt())
        val raw = mutableListOf<IntRange>()
        var start = -1
        var lastActive = -1

        for (y in 0 until h) {
            if (darkness[y] >= activeThreshold) {
                if (start < 0) start = y
                lastActive = y
            } else if (start >= 0 && y - lastActive > 5) {
                if (lastActive - start >= 7) raw += start..lastActive
                start = -1
                lastActive = -1
            }
        }
        if (start >= 0 && lastActive - start >= 7) raw += start..lastActive

        val merged = mutableListOf<IntRange>()
        raw.forEach { band ->
            if (merged.isEmpty()) {
                merged += band
            } else {
                val prev = merged.last()
                if (band.first - prev.last <= 12 && (band.last - prev.first) < 150) {
                    merged[merged.lastIndex] = prev.first..band.last
                } else {
                    merged += band
                }
            }
        }

        val crops = mutableListOf<Bitmap>()
        merged.take(80).forEach { band ->
            val top = max(0, band.first - 5)
            val bottom = min(h - 1, band.last + 5)
            val bh = bottom - top + 1
            if (bh < 10 || bh > 220) return@forEach

            var left = w - 1
            var right = 0
            for (y in top..bottom) {
                val base = y * w
                for (x in 0 until w) {
                    val c = pixels[base + x]
                    val gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
                    if (gray < 190) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }

            if (right <= left) return@forEach
            left = max(0, left - 10)
            right = min(w - 1, right + 10)
            val bw = right - left + 1
            if (bw < 12) return@forEach

            crops += Bitmap.createBitmap(scaled, left, top, bw, bh)
        }

        scaled.recycle()

        if (crops.isEmpty()) {
            val fallbackH = max(48, h / 20)
            var y = 0
            while (y < h && crops.size < 40) {
                val ch = min(fallbackH, h - y)
                crops += Bitmap.createBitmap(source, 0, min(y, source.height - 1), source.width, min(ch, source.height - min(y, source.height - 1)))
                y += fallbackH
            }
        }

        return crops
    }

    private fun prepareInput(line: Bitmap): FloatArray {
        val targetH = 48
        val targetW = 768
        val ratio = targetH.toFloat() / line.height.toFloat()
        val resizedW = min(targetW, max(1, (line.width * ratio).toInt()))
        val resized = Bitmap.createScaledBitmap(line, resizedW, targetH, true)

        val canvas = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(targetW * targetH) { Color.WHITE }
        val linePixels = IntArray(resizedW * targetH)
        resized.getPixels(linePixels, 0, resizedW, 0, 0, resizedW, targetH)

        val xOffset = targetW - resizedW
        for (y in 0 until targetH) {
            System.arraycopy(linePixels, y * resizedW, pixels, y * targetW + xOffset, resizedW)
        }

        val out = FloatArray(3 * targetH * targetW)
        val plane = targetH * targetW
        for (i in pixels.indices) {
            val c = pixels[i]
            out[i] = (Color.red(c) / 255f - 0.5f) / 0.5f
            out[plane + i] = (Color.green(c) / 255f - 0.5f) / 0.5f
            out[2 * plane + i] = (Color.blue(c) / 255f - 0.5f) / 0.5f
        }

        resized.recycle()
        canvas.recycle()
        return out
    }

    private fun decodeCtc(value: Any, dictionary: List<String>): Pair<String, Int> {
        @Suppress("UNCHECKED_CAST")
        val batch = value as? Array<Array<FloatArray>>
            ?: return "" to 0

        if (batch.isEmpty()) return "" to 0
        val timesteps = batch[0]
        if (timesteps.isEmpty()) return "" to 0

        val sb = StringBuilder()
        var last = -1
        var confidenceSum = 0.0
        var confidenceCount = 0

        for (scores in timesteps) {
            if (scores.isEmpty()) continue
            var best = 0
            var bestScore = scores[0]
            for (i in 1 until scores.size) {
                if (scores[i] > bestScore) {
                    best = i
                    bestScore = scores[i]
                }
            }

            if (best != 0 && best != last) {
                val dictIndex = best - 1
                if (dictIndex in dictionary.indices) {
                    sb.append(dictionary[dictIndex])
                    confidenceSum += probabilityLike(bestScore).toDouble()
                    confidenceCount++
                }
            }
            last = best
        }

        val raw = sb.toString().trim()
        val logical = visualPersianToLogical(raw)
        val conf = if (confidenceCount == 0) 0 else (confidenceSum / confidenceCount * 100.0).toInt().coerceIn(0, 100)
        return logical to conf
    }

    private fun probabilityLike(v: Float): Float {
        return when {
            v in 0f..1f -> v
            v > 20f -> 1f
            v < -20f -> 0f
            else -> (1.0 / (1.0 + kotlin.math.exp((-v).toDouble()))).toFloat()
        }
    }

    private fun visualPersianToLogical(text: String): String {
        if (text.none { it.code in 0x0600..0x06FF }) return text

        val tokens = Regex("""[0-9۰-۹٠-٩.,٬،:/+\-]+|[A-Za-z]+|\s+|.""").findAll(text).map { it.value }.toList()
        val reversed = tokens.asReversed().joinToString("")
        return reversed.replace(Regex("""\s+"""), " ").trim()
    }
}
