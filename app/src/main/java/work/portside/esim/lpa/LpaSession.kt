package work.portside.esim.lpa

import work.portside.esim.euicc.EuiccCard
import work.portside.esim.card.CardException
import work.portside.esim.util.B64

/**
 * Runs the full SGP.22 local-profile-assistant download: card challenge → SM-DP+
 * authentication → card authorization → prepare download → BoundProfilePackage → segmented
 * installation on the card → notification back to the server.
 *
 * Every call blocks; run it on a background thread. [confirmProfile] is invoked once the
 * server has shown what it will hand over (profile metadata), i.e. the last point where the
 * user can still back out cleanly.
 */
class LpaSession(
    private val card: EuiccCard,
    private val log: (String) -> Unit,
    private val confirmProfile: (Sgp22.ProfileMeta?) -> Boolean,
) {

    class Cancelled : Exception("Installation cancelled")
    class Outcome(val iccid: String?, val name: String?)

    fun install(code: ActivationCode, confirmationCode: String?): Outcome {
        log("Reading eUICC info from the card…")
        val info1 = Sgp22.euiccInfo1(card.es10b(Sgp22.getEuiccInfo1Request()))
        val challenge = Sgp22.euiccChallenge(card.es10b(Sgp22.getEuiccChallengeRequest()))

        log("Contacting ${code.smdp} …")
        val auth1 = Es9.initiateAuthentication(code.smdp, B64.encode(challenge), B64.encode(info1))
        val txidBin = runCatching { Sgp22.transactionIdFromServerSigned1(auth1.serverSigned1) }.getOrNull()

        try {
            log("Authenticating the server with the card…")
            val authenticateServerResponse = card.es10b(
                Sgp22.authenticateServerRequest(
                    auth1.serverSigned1,
                    auth1.serverSignature1,
                    auth1.euiccCiPKIdToBeUsed,
                    auth1.serverCertificate,
                    code.matchingId,
                ),
            )

            log("Authorizing the download…")
            val client = Es9.authenticateClient(code.smdp, auth1.transactionId, B64.encode(authenticateServerResponse))
            val meta = client.profileMetadata?.let { runCatching { Sgp22.profileMetadata(it) }.getOrNull() }
            if (!confirmProfile(meta)) throw Cancelled()

            log("Preparing the download…")
            val prepareResponse = card.es10b(
                Sgp22.prepareDownloadRequest(
                    client.smdpSigned2,
                    client.smdpSignature2,
                    client.smdpCertificate,
                    confirmationCode,
                ),
            )

            log("Downloading the profile from the server…")
            val bpp = Es9.getBoundProfilePackage(code.smdp, auth1.transactionId, B64.encode(prepareResponse))

            val segments = Sgp22.bppSegments(bpp)
            log("Installing on the card — ${segments.size} segments, don't touch the card…")
            var result: Sgp22.InstallResult? = null
            segments.forEachIndexed { index, segment ->
                val response = card.es10b(segment)
                if (response.isNotEmpty()) {
                    runCatching { Sgp22.parseInstallResult(response) }.getOrNull()?.let { result = it }
                }
                if (index % 5 == 4 || index == segments.size - 1) {
                    log("  … segment ${index + 1}/${segments.size}")
                }
            }

            val final = result ?: throw CardException("The card did not report an installation result")
            if (!final.success) throw CardException(final.describeError())

            notifyServer(code.smdp, final)
            return Outcome(final.iccid ?: meta?.iccid, meta?.profileName)
        } catch (e: Exception) {
            if (e !is Cancelled) cancel(code.smdp, auth1.transactionId, txidBin)
            throw e
        }
    }

    /** Best-effort: tell the server the installation result so it doesn't stay "in progress". */
    private fun notifyServer(smdp: String, result: Sgp22.InstallResult) {
        val seq = result.seqNumber ?: return
        try {
            val response = card.es10b(Sgp22.retrieveNotificationRequest(seq))
            val notification = Sgp22.notificationFromRetrieveResponse(response)
            Es9.handleNotification(smdp, B64.encode(notification))
            card.es10b(Sgp22.notificationSentRequest(seq))
            log("Reported the result to the server")
        } catch (e: Exception) {
            log("Note: could not report to the server (${e.message}); the notification stays queued on the card")
        }
    }

    /** Best-effort cancellation of a half-done download. */
    private fun cancel(smdp: String, transactionIdHttp: String?, transactionIdBin: ByteArray?) {
        if (transactionIdHttp == null || transactionIdBin == null) return
        try {
            val response = card.es10b(Sgp22.cancelSessionRequest(transactionIdBin, 0)) // 0 = end-user rejection
            Es9.cancelSession(smdp, transactionIdHttp, B64.encode(response))
            log("Cancelled the download session")
        } catch (e: Exception) {
            log("Note: could not cancel the session (${e.message})")
        }
    }
}
