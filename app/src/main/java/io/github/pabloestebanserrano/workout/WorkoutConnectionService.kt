package io.github.pabloestebanserrano.workout

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
/** the next three are to classify found devices and show only fitness hr sensors */
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
/** the next two are to specify what thread the GATT callbacks go to */
import android.os.Handler
import android.os.Looper
/** the next import is to help the GATT subscription take hold */
import android.util.Log

/**
 * Standard Bluetooth SIG UUIDs for the Heart Rate service and its
 * "Measurement" characteristic. These aren't Polar-specific — any BLE strap
 * that follows the Bluetooth Heart Rate Profile (the Verity Sense does)
 * exposes its data under these exact IDs. The CCC descriptor ("Client
 * Characteristic Configuration") is what you write to tell the peripheral
 * "start pushing me notifications."
 */
private val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val NOTIFICATION_CHANNEL_ID = "workout_tracking"
private const val NOTIFICATION_ID = 1

// Backoff schedule for auto-reconnect: 2s, 4s, 8s, 16s, then capped at 30s
// forever after. Capped rather than growing unbounded, and never giving up
// entirely, because the strap coming back into range 10 minutes into a
// workout should still reconnect automatically without the person having
// to notice and go pick it from a device list again.
private const val RECONNECT_BASE_DELAY_MS = 2_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L

/** Everything the UI might want to know about the current BLE connection. */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    object Reconnecting : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class FoundDevice(val name: String, val address: String, val device: BluetoothDevice)

/**
 * Owns the Bluetooth connection to the heart rate strap for as long as a
 * workout is running.
 *
 * WHY THIS IS A SERVICE, NOT PART OF THE ACTIVITY:
 * An Activity is tied to what's on screen — Android can destroy/recreate it
 * on rotation, and can kill its process outright when backgrounded. A
 * workout shouldn't stop because the screen turned off, so anything that
 * needs to survive that (this BLE connection, and the running
 * timer) lives here instead. Calling `startForeground()` is what tells
 * Android "a human asked for this, please don't kill it," in exchange for
 * showing an ongoing notification the whole time.
 *
 * The Activity *binds* to this Service to read its state, but the Service
 * keeps running regardless of whether anything is currently bound to it, as
 * long as it's in the foreground.
 */
@SuppressLint("MissingPermission") // every BLE call here is only reachable after the Activity has confirmed permissions are granted
class WorkoutConnectionService : Service() {

    /** The object a bound client (our Activity) uses to get a direct reference to this instance. */
    inner class LocalBinder : Binder() {
        fun getService(): WorkoutConnectionService = this@WorkoutConnectionService
    }

    private val binder = LocalBinder()

    private val bluetoothAdapter by lazy {
        getSystemService(BluetoothManager::class.java).adapter
    }

    private val database by lazy { WorkoutDatabase.getInstance(applicationContext) }

    // A scope tied to the Service's own lifetime rather than reusing
    // MainActivity's rememberCoroutineScope() — this matters specifically
    // because the record needs to finish saving even in the edge case where
    // the Activity has already been destroyed (workout running in the
    // background, user swipes the app away) while the Service — deliberately
    // built to outlive the Activity — is still very much alive and mid-way
    // through ending the workout. SupervisorJob means one failed coroutine
    // launched on this scope doesn't cancel any sibling coroutines.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gatt: BluetoothGatt? = null

    // Remembered so a dropped connection can be re-established by calling
    // connectGatt() on this same BluetoothDevice directly — no need to
    // re-scan, which would also be slower and would briefly clear the
    // found-devices list the UI is showing.
    private var lastConnectedDevice: BluetoothDevice? = null

    // Set to true right before *we* call gatt.disconnect() (picking a
    // different device, ending the workout) and read+reset the moment
    // STATE_DISCONNECTED arrives. This is what distinguishes "we asked for
    // this" from "the link just dropped" — deliberately not relying on the
    // GATT status code for that distinction, since how consistently
    // different Bluetooth stacks report status on a deliberate disconnect
    // vs. an actual drop isn't something to trust across OEMs (this project
    // has already hit one case of a stack behaving unlike a reference
    // device — see the earlier HR-notification debugging).
    private var intentionalDisconnect = false

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    // StateFlow holds a "current value" that observers can collect and get
    // updates from. We keep the mutable version private so nothing outside
    // this class can push fake state in; callers only ever see the
    // read-only view via `.asStateFlow()`.
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _foundDevices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val foundDevices: StateFlow<List<FoundDevice>> = _foundDevices.asStateFlow()

    // --- Workout state (new) ------------------------------------------------

    private val _workoutType = MutableStateFlow<WorkoutType?>(null)
    val workoutType: StateFlow<WorkoutType?> = _workoutType.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private var startedAtEpochMs: Long? = null
    private var pausedAtEpochMs: Long? = null
    private var pausedAccumulatedMs: Long = 0

    // Accumulated purely to compute average/peak HR for the history record
    // at the end of the workout — the live "current HR" display already has
    // its own separate StateFlow (_heartRate) above and doesn't touch these.
    private var hrSampleSum: Long = 0
    private var hrSampleCount: Int = 0
    private var hrPeak: Int = 0

    fun startWorkout(type: WorkoutType) {
        _workoutType.value = type
        startedAtEpochMs = System.currentTimeMillis()
        pausedAtEpochMs = null
        pausedAccumulatedMs = 0
        _isPaused.value = false
        hrSampleSum = 0
        hrSampleCount = 0
        hrPeak = 0
    }

    fun togglePause() {
        val now = System.currentTimeMillis()
        if (_isPaused.value) {
            pausedAtEpochMs?.let { pausedAccumulatedMs += now - it }
            pausedAtEpochMs = null
            _isPaused.value = false
        } else {
            pausedAtEpochMs = now
            _isPaused.value = true
        }
    }

    /**
     * Ends the workout, saving a history record first — same weightKg the
     * live screen already uses for its calorie display, so the saved figure
     * matches exactly what the person saw during the workout rather than
     * being recomputed later against whatever weight happens to be saved by
     * then.
     */
    fun endWorkout(weightKg: Double) {
        val type = _workoutType.value
        val startedAt = startedAtEpochMs
        disconnect()

        if (type != null && startedAt != null) {
            val elapsed = elapsedSecondsAt(System.currentTimeMillis())
            val record = WorkoutRecord(
                workoutType = type,
                startedAtEpochMs = startedAt,
                durationSeconds = elapsed,
                calories = estimateCalories(type.met, weightKg, elapsed),
                averageHeartRate = if (hrSampleCount > 0) (hrSampleSum / hrSampleCount).toInt() else null,
                peakHeartRate = hrPeak.takeIf { it > 0 },
            )
            // stopSelf() below only runs *after* the insert finishes, inside
            // this same coroutine, rather than being called separately right
            // after launch{}. stopSelf() schedules onDestroy() (which cancels
            // serviceScope) soon afterward, but "soon" isn't "never" or
            // "definitely after this insert lands" — sequencing it explicitly
            // here removes that race instead of hoping the timing works out.
            serviceScope.launch {
                database.workoutRecordDao().insert(record)
                resetWorkoutState()
                stopSelf()
            }
        } else {
            // Nothing meaningful to save (e.g. ended before startWorkout()
            // ever ran) — just clean up and stop, same as before.
            resetWorkoutState()
            stopSelf()
        }
    }

    private fun resetWorkoutState() {
        _workoutType.value = null
        startedAtEpochMs = null
        pausedAtEpochMs = null
        pausedAccumulatedMs = 0
        _isPaused.value = false
        hrSampleSum = 0
        hrSampleCount = 0
        hrPeak = 0
        // Without clearing these, a reconnect scheduled just before the
        // workout ended could still fire afterward and open a BLE
        // connection nothing is listening to anymore.
        reconnectJob?.cancel()
        reconnectAttempt = 0
        lastConnectedDevice = null
    }

    /**
     * Elapsed time computed fresh from real timestamps rather than from a
     * running counter, so it's automatically correct even after the app was
     * backgrounded and missed however many ticks — nothing to catch up on.
     */
    fun elapsedSecondsAt(nowMs: Long): Long {
        val started = startedAtEpochMs ?: return 0
        val pausedMs = pausedAccumulatedMs + (pausedAtEpochMs?.let { nowMs - it } ?: 0)
        return ((nowMs - started - pausedMs) / 1000).coerceAtLeast(0)
    }

    // ---------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Runs every time something calls startService()/startForegroundService().
     * We use it purely as the trigger to promote ourselves into the
     * foreground — binding alone does NOT do that, and Android requires
     * startForeground() to be called within seconds of a foreground start
     * or it'll crash the app.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        return START_STICKY // if the OS kills us under memory pressure, restart us
    }

    /** Hands our Binder to whoever calls bindService() — this is what makes it a *bound* service. */
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        gatt?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Public API — called by the Activity through the bound reference
    // ---------------------------------------------------------------------

    fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Turn on Bluetooth, then try again")
            return
        }
        _foundDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        // Filtering here means devices that don't advertise the Heart Rate
        // service — headphones, other phones, random gym gear — never even
        // reach onScanResult. Only actual HR sensors show up.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun connectTo(device: BluetoothDevice) {
        stopScan()
        lastConnectedDevice = device
        reconnectAttempt = 0
        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting
        updateNotification("Connecting...")
        connectToInternal(device)
    }

    /**
     * The actual connectGatt() call, shared between an explicit user pick
     * from the device list (connectTo) and an automatic retry after a drop
     * (scheduleReconnect) — the connection setup itself is identical either
     * way, only what happens *before* it (resetting backoff, updating
     * connection state to Connecting vs. Reconnecting) differs by caller.
     */
    private fun connectToInternal(device: BluetoothDevice) {
        // Pin GATT callbacks to the main thread explicitly, rather than leaving
        // it to "an unspecified background thread" (Android's own wording) —
        // that ambiguity is exactly the kind of thing that can behave
        // differently across Bluetooth chipsets/OEM stacks.
        val mainThreadHandler = Handler(Looper.getMainLooper())
        gatt = device.connectGatt(
            this,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            mainThreadHandler,
        )
    }

    fun disconnect() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        gatt?.disconnect()
    }

    /**
     * Schedules one reconnect attempt after a backoff delay. Re-checks
     * both that a workout is still running AND that `device` is still the
     * one we care about before actually reconnecting — either could have
     * changed during the delay (workout ended, or the person picked a
     * different device from a fresh scan) while this coroutine was asleep.
     */
    private fun scheduleReconnect(device: BluetoothDevice) {
        reconnectJob?.cancel()
        val delayMs = (RECONNECT_BASE_DELAY_MS shl reconnectAttempt).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        // Capped so the shift above can never be handed a runaway exponent —
        // harmless in practice since coerceAtMost already bounds the delay,
        // but there's no reason to let the counter climb forever either.
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (_workoutType.value != null && lastConnectedDevice === device) {
                connectToInternal(device)
            }
        }
    }

    // ---------------------------------------------------------------------
    // BLE callbacks — same logic as the spike, just living here now
    // ---------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            val current = _foundDevices.value
            if (current.none { it.address == device.address }) {
                _foundDevices.value = current + FoundDevice(name, device.address, device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        // Fires whenever the link goes up or down, including disconnects we
        // didn't ask for — see the STATE_DISCONNECTED branch below for the
        // auto-reconnect decision.
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connecting
                    reconnectAttempt = 0 // a successful link means backoff starts fresh next time
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _heartRate.value = null
                    updateNotification("Disconnected")

                    // Release this handle now rather than waiting for
                    // onDestroy(). Each connectGatt() call allocates a real
                    // OS-level resource, and opening a fresh one for a
                    // reconnect attempt while an old, already-dead handle is
                    // still open is a known way to eventually get spurious
                    // "status 133" failures from the Bluetooth stack itself.
                    g.close()
                    gatt = null

                    val wasIntentional = intentionalDisconnect
                    intentionalDisconnect = false
                    val device = lastConnectedDevice

                    if (!wasIntentional && _workoutType.value != null && device != null) {
                        _connectionState.value = ConnectionState.Reconnecting
                        scheduleReconnect(device)
                    } else {
                        _connectionState.value = ConnectionState.Idle
                    }
                }
            }
        }
        // overwriting the descriptor write to confirm whether the write succeeded or not
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("WorkoutService", "HR notifications enabled successfully")
            } else {
                Log.w("WorkoutService", "Enabling HR notifications FAILED, status=$status")
            }
        }

        // Once connected, Android has to read the peripheral's full list of
        // services/characteristics before we can use any of them. This
        // fires when that lookup finishes.
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(HEART_RATE_SERVICE_UUID)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (characteristic == null) {
                _connectionState.value = ConnectionState.Error("No Heart Rate service found on this device")
                return
            }
            // "Notification" is BLE terminology here: we're asking the strap
            // to push us new readings as they happen, instead of us having
            // to keep re-reading the characteristic ourselves.
            g.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)?.let { descriptor ->
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
            val name = g.device.name ?: g.device.address
            _connectionState.value = ConnectionState.Connected(name)
            updateNotification("Connected to $name")
        }

        // Fires on every new reading pushed by the subscribed characteristic.
        @Deprecated("Deprecated on newer Android, but still the callback invoked for this overload")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val bpm = parseHeartRate(characteristic.value)
                Log.d("WorkoutService", "HR notification received: $bpm bpm")
                _heartRate.value = bpm
                updateNotification("♥ $bpm bpm")
                hrSampleSum += bpm
                hrSampleCount++
                if (bpm > hrPeak) hrPeak = bpm
            }
        }
    }

    /**
     * The Heart Rate Measurement payload starts with a flags byte. Bit 0
     * says whether the BPM value that follows is packed as 1 byte (0-255,
     * the overwhelmingly common case) or 2 bytes (allowed by the spec, but
     * essentially never used in practice).
     */
    private fun parseHeartRate(data: ByteArray): Int {
        val flags = data[0].toInt()
        return if (flags and 0x01 == 0) {
            data[1].toInt() and 0xFF
        } else {
            ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        }
    }

    // ---------------------------------------------------------------------
    // Foreground notification — required for a foreground service to exist
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Workout tracking",
            NotificationManager.IMPORTANCE_LOW // LOW = shows in the shade, no sound/heads-up popup
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Workout in progress")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces) // placeholder — swap for a real icon later
            .setOngoing(true) // can't be swiped away while the service is running
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
