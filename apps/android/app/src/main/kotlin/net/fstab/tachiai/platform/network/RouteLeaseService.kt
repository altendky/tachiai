package net.fstab.tachiai.platform.network

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Network
import android.os.*
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

private fun bundle(fields: Map<String, Any>) = Bundle().apply {
    for ((key, value) in fields) when (value) {
        is String -> putString(key, value); is Long -> putLong(key, value); is Boolean -> putBoolean(key, value)
        // Only a renewal job's OPEN carries its Network (#275 stage 5).
        is Network -> putParcelable(key, value)
        else -> error("Unsupported private field")
    }
}

@Suppress("DEPRECATION")
private fun fields(bundle: Bundle): Map<String, Any>? = runCatching {
    RouteLeaseIpc.fields(bundle.keySet().associateWith { bundle.get(it) })
}.getOrNull()

// Ordinary-process route owner for other processes of this app. Leases come
// from the same process-wide RouteEngineOwner as local routes, so one device
// per key pair serves every process (#275).
class RouteLeaseService : Service() {
    companion object {
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "route-lease").apply { isDaemon = true } }
        // Unconfirmed closes keep their exact lease until process restart.
        private val retained = ConcurrentLinkedQueue<Any>()
    }
    private class Session(val grant: RouteLeaseGrant, val reply: Messenger, val death: IBinder.DeathRecipient) {
        var sequence = 1L
    }
    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<String, Session>() // Main-owned.
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val reply = message.replyTo ?: return
            val values = fields(message.data) ?: return
            message.data.clear()
            when (message.what) {
                RouteLeaseIpc.OPEN -> open(RouteLeaseIpc.open(values, SystemClock.elapsedRealtime()) ?: return, reply)
                RouteLeaseIpc.CLOSE -> {
                    val session = sessions[values["nonce"] as? String ?: return] ?: return
                    if (reply.binder != session.reply.binder) return
                    session.sequence = RouteLeaseIpc.close(values, session.grant.request.nonce, session.sequence) ?: return
                    session.grant.end()
                }
            }
        }
    })

    private fun open(request: RouteLeaseIpc.Open, reply: Messenger) {
        if (sessions.containsKey(request.nonce)) return
        lateinit var session: Session
        val grant = RouteLeaseGrant(request,
            grant = { open -> grantRouteLease(open, connectionProfileStore(this)::configured,
                WireGuardRouteProtocol::leaseLocallyForJob, WireGuardRouteProtocol::leaseLocally) },
            send = { what, values ->
                val data = bundle(values)
                try { reply.send(Message.obtain().apply { this.what = what; this.data = data }) } finally { data.clear() }
            },
            execute = workers::execute, now = SystemClock::elapsedRealtime,
            closed = { confirmed -> main.post {
                runCatching { reply.binder.unlinkToDeath(session.death, 0) }
                if (confirmed) { if (sessions[request.nonce] === session) sessions.remove(request.nonce) }
                else retained.add(session)
            } }, log = RouteEngineOwner::event)
        session = Session(grant, reply, IBinder.DeathRecipient { grant.end() })
        try { reply.binder.linkToDeath(session.death, 0) } catch (_: Throwable) { return }
        sessions[request.nonce] = session
        grant.start()
    }

    override fun onBind(intent: Intent): IBinder? = if (Build.VERSION.SDK_INT >= 28 &&
        Application.getProcessName() == packageName) messenger.binder else null
    override fun onDestroy() { sessions.values.toList().forEach { it.grant.end(revoke = true) }; super.onDestroy() }
}

// One binding per lease. BIND_IMPORTANT keeps the owner process alive at the
// playing viewer's priority; owner replies arrive off the main thread so a
// worker waiting for them never depends on main.
internal class AndroidRouteLeaseChannel(context: Context) : RouteLeaseChannel, ServiceConnection {
    companion object {
        private val replies by lazy { HandlerThread("route-lease-client").apply { start() }.looper }
    }
    private val application = context.applicationContext
    private val lock = Any()
    @Volatile private var events: RouteLeaseChannel.Events? = null
    private var remote: Messenger? = null
    private var binder: IBinder? = null
    private var bound = false
    private val death = IBinder.DeathRecipient { events?.lost() }
    private val reply by lazy { Messenger(object : Handler(replies) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val values = fields(message.data) ?: return
            message.data.clear()
            events?.received(message.what, values)
        }
    }) }

    override fun connect(events: RouteLeaseChannel.Events): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        synchronized(lock) { this.events = events }
        reply // Created off main before any reply can arrive.
        val ok = application.bindService(Intent(application, RouteLeaseService::class.java), this,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        synchronized(lock) { bound = ok }
        return ok
    }

    // Only the first connection opens; a restarted owner never receives a new OPEN.
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        synchronized(lock) {
            if (remote != null) return
            try { service.linkToDeath(death, 0) } catch (_: Throwable) { events?.lost(); return }
            binder = service; remote = Messenger(service)
        }
        events?.connected()
    }
    override fun onServiceDisconnected(name: ComponentName) { events?.lost() }
    override fun onBindingDied(name: ComponentName) { events?.lost() }
    override fun onNullBinding(name: ComponentName) { events?.lost() }

    override fun send(what: Int, fields: Map<String, Any>) {
        val target = synchronized(lock) { checkNotNull(remote) }
        val data = bundle(fields)
        try { target.send(Message.obtain().apply { this.what = what; replyTo = reply; this.data = data }) } finally { data.clear() }
    }

    override fun release() {
        val (link, unbind) = synchronized(lock) {
            (binder to bound).also { binder = null; remote = null; bound = false }
        }
        link?.let { runCatching { it.unlinkToDeath(death, 0) } }
        if (unbind) application.unbindService(this)
    }

    override fun toString() = "AndroidRouteLeaseChannel(status only)"
}

// Installed once per process from Application.onCreate.
internal fun installRouteLeaseSource(context: Context) {
    if (Build.VERSION.SDK_INT < 28 || !routeLeasesFromOwner(Application.getProcessName(), context.packageName)) return
    val application = context.applicationContext
    WireGuardRouteProtocol.leases = { profile, security, preparation ->
        RemoteRouteLeaseBackend.open(AndroidRouteLeaseChannel(application), profile, security, preparation,
            SystemClock::elapsedRealtime, log = RouteEngineOwner::event)
    }
    WireGuardRouteProtocol.jobLeases = { profile, security, preparation, job ->
        RemoteRouteLeaseBackend.open(AndroidRouteLeaseChannel(application), profile, security, preparation,
            SystemClock::elapsedRealtime, job = job, log = RouteEngineOwner::event)
    }
}
