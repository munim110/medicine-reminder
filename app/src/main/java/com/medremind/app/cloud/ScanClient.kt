package com.medremind.app.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.medremind.app.data.FoodRule
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One medicine read off a prescription. Unverified until the user reviews it. */
data class ScannedMedicine(
    val name: String,
    val form: String?,
    val strength: String?,
    /** The dose pattern exactly as written, e.g. `1+0+1`. The four slots below are parsed from it. */
    val doseText: String?,
    val morning: Double?,
    val noon: Double?,
    val evening: Double?,
    val night: Double?,
    /** `daily`, `weekly`, `monthly` or `as_needed`. The schedule only models daily doses so far. */
    val frequency: String?,
    val foodRule: FoodRule?,
    /**
     * The duration exactly as written, e.g. `২০ দিন` or `চলবে`. Information for the reviewer only:
     * no end date is set, the family deletes a medicine when its course is over.
     */
    val durationText: String?,
    val instructions: String?,
    /** `high`, `medium` or `low`. Anything not `high` must be checked against the paper. */
    val confidence: String,
)

data class ScanResult(
    val isPrescription: Boolean,
    val medicines: List<ScannedMedicine>,
    val notes: String?,
    val scansLeftToday: Int,
)

sealed interface ScanOutcome {
    data class Success(val result: ScanResult) : ScanOutcome
    data class Failure(val reason: ScanError) : ScanOutcome
}

enum class ScanError { OFFLINE, DAILY_LIMIT, BUSY, UNREADABLE_IMAGE, FAILED }

/**
 * Sends a prescription photo to the scan Worker (`server/scan-worker`), which holds the AI key.
 * The key never ships in the app.
 */
object ScanClient {

    private const val SCAN_URL = "https://medremind-scan.munim987654321.workers.dev/scan"

    /** Long enough for small handwriting to survive, small enough to upload quickly on mobile data. */
    private const val MAX_SIDE = 1600

    suspend fun scan(context: Context, photo: Uri): ScanOutcome = withContext(Dispatchers.IO) {
        val jpeg = try {
            encodeJpeg(context, photo)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read photo", e)
            return@withContext ScanOutcome.Failure(ScanError.UNREADABLE_IMAGE)
        }
        try {
            post(jpeg, CloudSession.idToken())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Scan request failed", e)
            ScanOutcome.Failure(ScanError.OFFLINE)
        } catch (e: FirebaseNetworkException) {
            Log.w(TAG, "Sign-in failed: no network", e)
            ScanOutcome.Failure(ScanError.OFFLINE)
        } catch (e: Exception) {
            Log.w(TAG, "Scan failed", e)
            ScanOutcome.Failure(ScanError.FAILED)
        }
    }

    private fun post(jpeg: ByteArray, token: String): ScanOutcome {
        val conn = URL(SCAN_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(jpeg.size)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.outputStream.use { it.write(jpeg) }

            return when (val code = conn.responseCode) {
                200 -> ScanOutcome.Success(parse(conn.inputStream.bufferedReader().use { it.readText() }))
                429 -> {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    ScanOutcome.Failure(
                        if (body.contains("daily_limit_reached")) ScanError.DAILY_LIMIT else ScanError.BUSY,
                    )
                }
                else -> {
                    Log.w(TAG, "Scan HTTP $code")
                    ScanOutcome.Failure(ScanError.FAILED)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): ScanResult {
        val json = JSONObject(body)
        val list = json.optJSONArray("medicines")
        val medicines = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val m = list!!.optJSONObject(i) ?: return@mapNotNull null
            ScannedMedicine(
                name = m.str("name") ?: return@mapNotNull null,
                form = m.str("form"),
                strength = m.str("strength"),
                doseText = m.str("doseText"),
                morning = m.num("morning"),
                noon = m.num("noon"),
                evening = m.num("evening"),
                night = m.num("night"),
                frequency = m.str("frequency"),
                foodRule = when (m.str("food")) {
                    "before" -> FoodRule.BEFORE_FOOD
                    "after" -> FoodRule.AFTER_FOOD
                    "empty_stomach" -> FoodRule.EMPTY_STOMACH
                    "any" -> FoodRule.ANY
                    else -> null
                },
                durationText = m.str("durationText"),
                instructions = m.str("instructions"),
                confidence = m.str("confidence") ?: "low",
            )
        }
        return ScanResult(
            isPrescription = json.optBoolean("isPrescription"),
            medicines = medicines,
            notes = json.str("notes"),
            scansLeftToday = json.optInt("scansLeftToday"),
        )
    }

    /** Decodes, scales the long side down to [MAX_SIDE] and re-encodes as JPEG. */
    private fun encodeJpeg(context: Context, photo: Uri): ByteArray {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the EXIF rotation, so sideways phone photos arrive upright.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photo)) { decoder, info, _ ->
                val scale = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt(),
                        (info.size.height * scale).roundToInt(),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(context, photo)
        }
        return ByteArrayOutputStream().use { out ->
            decoded.compress(Bitmap.CompressFormat.JPEG, 85, out)
            decoded.recycle()
            out.toByteArray()
        }
    }

    /** API 26–27. Ignores EXIF rotation; the model still reads rotated text, just less reliably. */
    private fun decodeLegacy(context: Context, photo: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(photo).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(photo).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("not an image")
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true,
        ).also { bitmap.recycle() }
    }

    private const val TAG = "ScanClient"
}

/** `optString` turns JSON null into the text "null"; this returns a real null instead. */
private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.num(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
