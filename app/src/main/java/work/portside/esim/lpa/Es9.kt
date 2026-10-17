package work.portside.esim.lpa

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ES9+ client — the LPA side of SGP.22 that talks to the SM-DP+ server over HTTPS.
 * Bodies are JSON with Base64-encoded ASN.1 values; the headers mirror the GSMA reference
 * LPAD (`gsma-rsp-lpad` / `gsma/rsp/v2.2.2`).
 */
object Es9 {

    class SmdpException(message: String) : Exception(message)

    data class InitiateAuth(
        val transactionId: String,
        val serverSigned1: String,
        val serverSignature1: String,
        val euiccCiPKIdToBeUsed: String,
        val serverCertificate: String,
    )

    data class AuthClient(
        val profileMetadata: String?,
        val smdpSigned2: String,
        val smdpSignature2: String,
        val smdpCertificate: String,
    )

    fun initiateAuthentication(smdp: String, euiccChallengeB64: String, euiccInfo1B64: String): InitiateAuth {
        val body = JSONObject()
            .put("smdpAddress", smdp)
            .put("euiccChallenge", euiccChallengeB64)
            .put("euiccInfo1", euiccInfo1B64)
        val r = post(smdp, "/gsma/rsp2/es9plus/initiateAuthentication", body)
        return InitiateAuth(
            transactionId = str(r, "transactionId"),
            serverSigned1 = str(r, "serverSigned1"),
            serverSignature1 = str(r, "serverSignature1"),
            euiccCiPKIdToBeUsed = str(r, "euiccCiPKIdToBeUsed"),
            serverCertificate = str(r, "serverCertificate"),
        )
    }

    fun authenticateClient(smdp: String, transactionId: String, authenticateServerResponseB64: String): AuthClient {
        val body = JSONObject()
            .put("transactionId", transactionId)
            .put("authenticateServerResponse", authenticateServerResponseB64)
        val r = post(smdp, "/gsma/rsp2/es9plus/authenticateClient", body)
        return AuthClient(
            profileMetadata = r.optString("profileMetadata").takeIf { it.isNotEmpty() },
            smdpSigned2 = str(r, "smdpSigned2"),
            smdpSignature2 = str(r, "smdpSignature2"),
            smdpCertificate = str(r, "smdpCertificate"),
        )
    }

    fun getBoundProfilePackage(smdp: String, transactionId: String, prepareDownloadResponseB64: String): String {
        val body = JSONObject()
            .put("transactionId", transactionId)
            .put("prepareDownloadResponse", prepareDownloadResponseB64)
        val r = post(smdp, "/gsma/rsp2/es9plus/getBoundProfilePackage", body)
        return str(r, "boundProfilePackage")
    }

    fun handleNotification(smdp: String, pendingNotificationB64: String) {
        val body = JSONObject().put("pendingNotification", pendingNotificationB64)
        post(smdp, "/gsma/rsp2/es9plus/handleNotification", body)
    }

    fun cancelSession(smdp: String, transactionId: String, cancelSessionResponseB64: String) {
        val body = JSONObject()
            .put("transactionId", transactionId)
            .put("cancelSessionResponse", cancelSessionResponseB64)
        post(smdp, "/gsma/rsp2/es9plus/cancelSession", body)
    }

    // ------------------------------------------------------------------ plumbing

    private fun post(smdp: String, path: String, body: JSONObject): JSONObject {
        val url = URL("https://$smdp$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "gsma-rsp-lpad")
            conn.setRequestProperty("X-Admin-Protocol", "gsma/rsp/v2.2.2")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                throw SmdpException("SM-DP+ answered HTTP $code${describeError(text)?.let { ": $it" } ?: ""}")
            }
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw SmdpException("SM-DP+ sent a response that is not JSON")
            }
            return json
        } catch (e: SmdpException) {
            throw e
        } catch (e: Exception) {
            throw SmdpException("Could not reach the SM-DP+ server: ${e.message}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) {
            throw SmdpException(errorMessage(o) ?: "SM-DP+ response is missing \"$key\"")
        }
        return o.getString(key)
    }

    private fun errorMessage(o: JSONObject): String? {
        val data = o.optJSONObject("header")
            ?.optJSONObject("functionExecutionStatus")
            ?.optJSONObject("statusCodeData") ?: return null
        val message = data.optString("message").takeIf { it.isNotEmpty() }
        val subject = data.optString("subjectCode").takeIf { it.isNotEmpty() }
        val reason = data.optString("reasonCode").takeIf { it.isNotEmpty() }
        return when {
            message != null -> message
            subject != null || reason != null -> "server reported $subject/$reason"
            else -> null
        }
    }

    private fun describeError(text: String): String? {
        if (text.isBlank()) return null
        return runCatching { errorMessage(JSONObject(text)) }.getOrNull()
            ?: text.take(200)
    }
}
