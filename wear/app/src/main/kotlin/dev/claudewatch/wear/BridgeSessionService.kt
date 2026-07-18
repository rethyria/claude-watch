package dev.claudewatch.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.claudewatch.wear.net.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The chip's one line of truth, PURE so plain-JVM tests can table it: the
 * OngoingActivity status text for a [ConnectionState]. Deliberately terse —
 * the chip is a sliver on a round watch face, and "paired, reconnecting
 * (stream failure: timeout)" would render as "paired, reco…". Connecting and
 * Reconnecting collapse into one word on purpose: from the wrist they are the
 * same promise ("the watch is working on it"), and the attempt counter is
 * noise at chip size. Everything else is the state's own name, lowercased.
 */
fun serviceStatusText(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> "connected"
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> "reconnecting"
    ConnectionState.Stopped -> "stopped"
    ConnectionState.Pairing -> "pairing"
    is ConnectionState.PairFailed -> "pair failed"
    is ConnectionState.ProtoMismatch -> "proto mismatch"
    is ConnectionState.AuthExpired -> "auth expired"
    is ConnectionState.BridgeMismatch -> "bridge mismatch"
}

/**
 * The connectedDevice foreground service that gives the bridge connection a
 * PROCESS lifetime (issue #24). Before it, the SSE stream died with
 * MainActivity's ViewModelStore, and Wear's Doze killed a naked socket
 * anyway; now the connection is held open by this started (never bound)
 * service, surfaced as an OngoingActivity chip on the watch face.
 *
 * Lifecycle contract:
 *  - MainActivity starts it when the UI state turns paired (while RESUMED —
 *    the Android 12+ FGS-from-foreground rules).
 *  - onCreate builds the notification FIRST and calls startForeground
 *    IMMEDIATELY: startForegroundService gives 5 seconds before an ANR, and
 *    everything else (the state collector) can wait.
 *  - onStartCommand is START_STICKY and fires [BridgeViewModel.resume] — a
 *    system restart of the sticky service revives the connection with no
 *    activity involved (start() no-ops on a live engine, stays Stopped
 *    without credentials).
 *  - The service watches [BridgeViewModel.connection] and stops itself on
 *    the terminal states (Stopped / AuthExpired / BridgeMismatch): nothing
 *    left to keep alive, and a foreground chip for a dead connection would
 *    be a lie on the watch face.
 *  - The notification's Disconnect action is the user's stop affordance:
 *    [BridgeViewModel.disconnect] (stream down, credentials KEPT — reopening
 *    the app resumes and catches up from the persisted cursor) + stopSelf.
 */
class BridgeSessionService : Service() {

    // Main.immediate like the UI's collectors: state updates land on the
    // main thread where the NotificationManager calls are cheap and ordered.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The state watch, cancellable SEPARATELY from the scope: both death
    // paths cancel it BEFORE stopForeground, so a state emission landing in
    // the stopSelf→onDestroy window can never notify() an orphaned
    // ongoing-flagged notification back into existence (with a Disconnect
    // action pointing at a corpse). Main-thread only, like everything else
    // in this service.
    private var watchJob: Job? = null

    // The ONE builder, kept for the service's lifetime: OngoingActivity.apply
    // serializes the chip extras INTO it, so re-posting from the same builder
    // keeps the notification ongoing-activity-tagged. A fresh builder per
    // update would silently shed the chip.
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var ongoingActivity: OngoingActivity? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bridge session",
            // Low importance: no sound, no heads-up — the chip on the watch
            // face IS the surface; the notification is its carrier.
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val vm = BridgeViewModel.singleton(applicationContext)
        val initialText = serviceStatusText(vm.connection.value)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // The stop affordance: a self-addressed ACTION_DISCONNECT intent —
        // handled in onStartCommand as disconnect (credentials kept) + die.
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeSessionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_chip)
            .setContentTitle("Claude Watch")
            .setContentText(initialText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Disconnect", disconnectIntent)

        // The OngoingActivity must attach BEFORE the notification is posted
        // (apply() writes its extras into the builder; a post-then-apply
        // never reaches the watch face chip).
        ongoingActivity = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
            .setStaticIcon(R.drawable.ic_bridge_chip)
            .setTouchIntent(contentIntent)
            .setStatus(chipStatus(initialText))
            .build()
            .also { it.apply(applicationContext) }

        // Immediately: the 5s startForegroundService deadline. minSdk 30, so
        // the 3-arg overload (API 29+) is always available — the type only
        // gets ENFORCED against the manifest permissions on API 34+.
        startForeground(
            NOTIFICATION_ID,
            notificationBuilder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        startWatching(vm)
    }

    /**
     * (Re)starts the connection-state watch. collectLatest, because the
     * terminal branch DELAYS: at a sticky cold restart the engine is still
     * reading its persisted credentials off disk (resume() is async), so the
     * flow's first value can be an entirely temporary Stopped. A live state
     * landing inside the grace cancels the pending death; a Stopped that
     * outlives it is real (no credentials, or a user disconnect) and the
     * service must not sit on the watch face lying about it. Restartable
     * because a start command can land on an instance whose watch already
     * decided to die (stopSelf sent, onDestroy pending): the fresh collector
     * re-evaluates the CURRENT state — StateFlow would never re-emit an
     * unchanged terminal value to the old, already-run collectLatest block,
     * so without the restart a resurrected service would sit foreground
     * forever with a dead chip.
     */
    private fun startWatching(vm: BridgeViewModel) {
        watchJob?.cancel()
        watchJob = scope.launch {
            vm.connection.collectLatest { state ->
                if (state.isTerminal()) {
                    delay(TERMINAL_GRACE_MS)
                    // Kill our own watch before the foreground teardown: no
                    // later emission may notify() past the stop (see watchJob).
                    watchJob?.cancel()
                    ServiceCompat.stopForeground(
                        this@BridgeSessionService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                } else {
                    val text = serviceStatusText(state)
                    notificationBuilder.setContentText(text)
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notificationBuilder.build())
                    ongoingActivity?.update(this@BridgeSessionService, chipStatus(text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // The user's stop affordance: stream down, credentials KEPT (the
            // whole difference from unpair) — reopening the app resume()s and
            // catches up from the persisted cursor. Explicit teardown here
            // instead of riding the state collector's grace: the user just
            // tapped Disconnect and the chip must go NOW, not in 3 seconds.
            watchJob?.cancel() // no post-stop notify (see watchJob's doc)
            BridgeViewModel.singleton(applicationContext).disconnect()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // EVERY non-disconnect start must re-assert foreground: MainActivity
        // fires startForegroundService on each re-entry to RESUMED (its
        // repeatOnLifecycle collector restarts), and the platform demands a
        // startForeground() for EACH of those — on a live instance onCreate
        // does not re-run, and skipping the call here is the 10-second
        // "Context.startForegroundService() did not then call
        // Service.startForeground()" crash. Re-posting from the retained
        // builder is idempotent, keeps the OngoingActivity extras (see the
        // builder's doc), and doubles as the chip's recovery after a
        // POST_NOTIFICATIONS grant: the permission dialog pauses the
        // activity, its dismissal re-RESUMES it, MainActivity re-starts us,
        // and this call posts the once-suppressed notification for real.
        startForeground(
            NOTIFICATION_ID,
            notificationBuilder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val vm = BridgeViewModel.singleton(applicationContext)
        // Every (re)start — MainActivity's paired trigger AND the system
        // reviving the sticky service after killing the process — resumes the
        // engine. Guarded upstream: a live engine no-ops.
        vm.resume()
        // A start that raced a decided death resurrects the service; restart
        // the watch so the current state is re-judged (see startWatching).
        if (watchJob?.isActive != true) startWatching(vm)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Belt and braces against any interleaving this instance lost track
        // of: a destroyed service must leave no ongoing-flagged notification
        // behind (its Disconnect action would point at nothing).
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null // started, never bound

    private fun chipStatus(text: String): Status =
        Status.Builder().addPart("status", Status.TextPart(text)).build()

    /** Nothing to keep alive: dead by user choice or dead-token/wrong-bridge. */
    private fun ConnectionState.isTerminal(): Boolean =
        this is ConnectionState.Stopped ||
            this is ConnectionState.AuthExpired ||
            this is ConnectionState.BridgeMismatch

    companion object {
        const val CHANNEL_ID = "bridge_session"
        const val NOTIFICATION_ID = 24 // the issue that demanded the service
        const val ACTION_DISCONNECT = "dev.claudewatch.wear.action.DISCONNECT"

        /**
         * How long an initial terminal state may persist before the service
         * concludes there is genuinely nothing to keep alive. Covers the
         * async resume() at a sticky cold restart: the DataStore read plus
         * Keystore decrypt land well inside this on real hardware.
         */
        internal const val TERMINAL_GRACE_MS = 3_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeSessionService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeSessionService::class.java))
        }
    }
}
