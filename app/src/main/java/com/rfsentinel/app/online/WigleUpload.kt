package com.rfsentinel.app.online

import android.content.Context
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.util.WigleLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads the WiGLE log files ([WigleLog]) to wigle.net (api.wigle.net, file/upload), with the
 * user's own WiGLE API name and token (the same account as "Find on WiGLE"). What's sent: each file
 * as it is, with the exact positions where this phone heard each network. WiGLE adds them to its map
 * under the user's name; an uploaded file is kept on the phone (Delete uploaded files removes them).
 */
object WigleUpload {

    private const val ENDPOINT = "https://api.wigle.net/api/v2/file/upload"
    private const val TAG = "WigleUpload"

    class Outcome(val file: File, val ok: Boolean, val message: String)

    /**
     * Closes the file being written, then uploads every file waiting, oldest first. Stops at the first
     * refusal of the account (it would refuse the rest too). [status] hears each step.
     */
    suspend fun uploadAll(context: Context, status: (String) -> Unit = {}): List<Outcome> = withContext(Dispatchers.IO) {
        WigleLog.close()
        val auth = Wigle.authHeader(context) ?: throw Wigle.Failure("Enter your WiGLE API name and token first")
        val files = WigleLog.pending(context)
        val done = mutableListOf<Outcome>()
        for ((i, f) in files.withIndex()) {
            status("Uploading ${i + 1} of ${files.size}: ${f.name}")
            val o = try {
                val msg = upload(f, auth)
                WigleLog.markUploaded(context, f)
                Outcome(f, true, msg)
            } catch (e: Wigle.Failure) {
                Outcome(f, false, e.message ?: "WiGLE refused it")
            }
            done += o
            if (!o.ok && o.message.startsWith(ACCOUNT_REFUSED)) break
        }
        done
    }

    private const val ACCOUNT_REFUSED = "WiGLE didn't accept the API name and token"

    /** One file as multipart/form-data, field "file", as WiGLE's own app sends it. Returns WiGLE's word on it. */
    private fun upload(f: File, auth: String): String {
        val boundary = "----RFSentinel" + System.nanoTime()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${f.name}\"\r\n" +
            "Content-Type: text/csv\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Authorization", auth)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(head.size + f.length() + tail.size)
        }
        try {
            c.outputStream.use { o ->
                o.write(head)
                f.inputStream().use { it.copyTo(o) }
                o.write(tail)
            }
            val code = c.responseCode
            val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
            android.util.Log.i(TAG, "upload answered HTTP $code")
            when (code) {
                in 200..299 -> return parse(body)
                401, 403 -> throw Wigle.Failure(ACCOUNT_REFUSED)
                413 -> throw Wigle.Failure("The file is too big for WiGLE")
                429 -> throw Wigle.Failure("WiGLE asks to slow down: try again later")
                else -> throw Wigle.Failure("WiGLE answered HTTP $code" + message(body)?.let { ": $it" }.orEmpty())
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw Wigle.Failure("WiGLE took too long to answer: try again later")
        } catch (e: java.io.IOException) {
            throw Wigle.Failure("Couldn't reach WiGLE: ${e.message ?: "no connection"}")
        } finally {
            c.disconnect()
        }
    }

    /** {"success":true,"results":{"transid":"20261119-00123",...},"warning":...} as words. */
    internal fun parse(body: String): String {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return "Uploaded"
        if (!o.optBoolean("success", true)) throw Wigle.Failure(o.optString("message").ifBlank { "WiGLE refused the file" })
        val trans = o.optJSONObject("results")?.optString("transid")?.takeIf { it.isNotBlank() && it != "null" }
        val warning = o.optString("warning").takeIf { it.isNotBlank() && it != "null" }
        return listOfNotNull(trans?.let { "Uploaded, transaction $it" } ?: "Uploaded", warning).joinToString(" - ")
    }

    private fun message(body: String): String? =
        runCatching { JSONObject(body).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}
