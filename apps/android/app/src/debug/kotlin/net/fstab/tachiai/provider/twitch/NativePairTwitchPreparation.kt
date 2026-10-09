package net.fstab.tachiai.provider.twitch

import android.content.Context
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.net.AccessProbeHttp

// Same-device saved LOCAL grant only. Worker owns validation/source resolution;
// no token, signed URI or account field enters intents, UI, logs or toString.
internal class NativePairTwitchPreparation(context: Context, private val active: () -> Boolean,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val cache: TwitchSavedAuthorization = AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL),
) : AutoCloseable {
    init { require(cache.profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL) }
    private val valid = AtomicBoolean(true)
    private val validator = AtomicReference<TwitchDeviceHttpTransport?>()
    private val access = AtomicReference<AccessProbeHttp?>()
    @Volatile private var lease: SavedAuthorizationLease? = null
    private var checkedMs = Long.MIN_VALUE
    private val storedLock = Any()
    var acceptanceDeadlineMs = 0L
        private set

    // Cheap state only: budget/timing callbacks may run on the main looper.
    fun canContinue(): Boolean = valid.get() && active() && lease?.let {
        cache.isCurrent(it) && cache.remainingLocalMs(it) > 0
    } == true

    // Worker/Media3 loader only. Bound repeated disk/decryption checks to once
    // per second; preparation checks and the five-second worker poll use force.
    fun checkStored(force: Boolean = false): Boolean = synchronized(storedLock) {
        if (!canContinue()) return false
        val now = System.nanoTime() / 1_000_000
        if (!force && checkedMs != Long.MIN_VALUE && now >= checkedMs && now - checkedMs < 1_000) return true
        val current = lease?.let(cache::isStoredCurrent) == true
        checkedMs = now
        if (!current) valid.set(false)
        current && canContinue()
    }

    fun resolve(replay: Boolean, onStatus: (String, Int) -> Unit): TwitchPlaybackSource =
        resolve(replay, if (replay) "2080217716" else "relaxbeats", onStatus)

    // Explicit public resource identity; the historical overload above is unchanged.
    fun resolve(replay: Boolean, resource: String, onStatus: (String, Int) -> Unit): TwitchPlaybackSource {
        check(valid.get() && active())
        val stored = cache.read()
        onStatus("STORAGE_${stored.state.name}", 0)
        lease = stored.lease ?: throw IOException("Pair authorization unavailable")
        if (!checkStored(force = true)) throw IOException("Pair authorization ended")
        var selected: TwitchPlaybackSource? = null
        val validation = TwitchDeviceHttpTransport(open = openConnection, canRequest = {
            checkStored(force = true)
        }, onHttpStatus = { endpoint, code -> onStatus(endpoint.name, code) })
        validator.set(validation)
        try {
            val result = runBlocking {
                useSavedTwitchAuthorization(cache, validation, DeviceAuthorizationForeground(true), onUse = { token, deadline, accepted ->
                    lease = accepted
                    acceptanceDeadlineMs = deadline
                    if (!checkStored(force = true)) throw IOException("Pair authorization ended")
                    val request = AccessProbeHttp(open = openConnection, canRequest = {
                        checkStored(force = true) && System.nanoTime() / 1_000_000 < deadline
                    })
                    access.set(request)
                    try {
                        selected = resolveTwitchPlayback(request, if (replay) TwitchAccessCase.REPLAY else TwitchAccessCase.LIVE,
                            resource, token,
                            onHttpStatus = { onStatus("ACCESS", it) })
                        if (!checkStored(force = true)) selected = null
                    } finally { access.compareAndSet(request, null); request.close() }
                })
            }
            onStatus(result.outcome.name, result.validationHttp)
            return selected?.takeIf { result.outcome == SavedTwitchUseOutcome.USED && checkStored(force = true) }
                ?: throw IOException("Pair source unavailable")
        } finally { validator.compareAndSet(validation, null); validation.close() }
    }

    override fun close() {
        valid.set(false)
        validator.getAndSet(null)?.close()
        access.getAndSet(null)?.close()
    }
}
