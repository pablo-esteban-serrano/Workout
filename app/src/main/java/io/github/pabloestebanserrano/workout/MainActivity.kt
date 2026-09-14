package io.github.pabloestebanserrano.workout

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object Settings : Screen()
    object History : Screen()
    data class Active(val type: WorkoutType) : Screen()
}

class MainActivity : ComponentActivity() {

    private val boundService = mutableStateOf<WorkoutConnectionService?>(null)

    // `by lazy` so it's created on first use rather than at construction
    // time (before `this` is fully a valid Context) — created once, reused
    // for the Activity's whole lifetime, same idea as the DataStore
    // singleton-per-file guarantee inside the repository itself.
    private val userProfileRepository by lazy { UserProfileRepository(applicationContext) }

    // Same lazy-singleton reasoning as userProfileRepository — created once,
    // reused for the Activity's lifetime. WorkoutConnectionService gets its
    // own reference the same way (see WorkoutDatabase.getInstance), and both
    // end up pointed at the identical underlying database instance thanks
    // to that class's synchronized singleton accessor.
    private val database by lazy { WorkoutDatabase.getInstance(applicationContext) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            boundService.value = (service as WorkoutConnectionService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boundService.value = null
        }
    }

    private fun requiredPermissions(): Array<String> {
        val ble = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ble + android.Manifest.permission.POST_NOTIFICATIONS else ble
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun beginTracking() {
        startForegroundService(Intent(this, WorkoutConnectionService::class.java))
        boundService.value?.startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Puts Compose in charge of insets (status bar, nav bar, keyboard)
        // instead of relying on the OS to resize/pan the window around them
        // itself. This is what makes Modifier.imePadding() in SettingsScreen
        // reliable — without it, keyboard-avoidance behavior is left up to
        // whatever the specific OS/launcher does by default, which is the
        // inconsistency that was causing the Light Phone to behave
        // differently from e/OS in the first place covering the save button with the keyboard.
        enableEdgeToEdge()
        bindService(Intent(this, WorkoutConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)

        setContent {
            var screen by remember { mutableStateOf<Screen>(Screen.Home) }
            val service = boundService.value
            val coroutineScope = rememberCoroutineScope()

            // Stays in sync automatically: whenever save() writes new values
            // (from the Settings screen below), this collector gets pushed
            // the update and `profile` here just... changes. No manual
            // refresh call needed anywhere.
            val profile by userProfileRepository.profile.collectAsStateWithLifecycle(
                initialValue = UserProfileRepository.DEFAULT_PROFILE
            )
            val workoutHistory by database.workoutRecordDao().getAll().collectAsStateWithLifecycle(
                initialValue = emptyList()
            )

            // If a workout is already running in the service (e.g. the Activity
            // process was recreated while the workout kept going in the
            // background), jump straight back to the Active screen instead of
            // showing Home over a workout that's actually still in progress.
            LaunchedEffect(service) {
                service?.workoutType?.collect { type -> if (type != null) screen = Screen.Active(type) }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results -> if (results.values.all { it }) beginTracking() }

            LightWorkoutTheme(inverted = profile.invertColors) {
                when (val current = screen) {
                    is Screen.Home -> HomeScreen(
                        onSelectWorkout = { type ->
                            service?.startWorkout(type)
                            screen = Screen.Active(type)
                            if (hasPermissions()) beginTracking() else permissionLauncher.launch(requiredPermissions())
                        },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenHistory = { screen = Screen.History },
                    )

                    is Screen.History -> WorkoutHistoryScreen(
                        records = workoutHistory,
                        onBack = { screen = Screen.Home },
                        onDelete = { record ->
                            // Same coroutineScope already used for saving the
                            // profile in Settings — a delete is a one-shot
                            // suspend call, no need for a dedicated scope.
                            coroutineScope.launch {
                                database.workoutRecordDao().delete(record)
                            }
                        },
                    )

                    is Screen.Settings -> SettingsScreen(
                        initialAge = profile.age,
                        initialWeightKg = profile.weightKg,
                        initialInvertColors = profile.invertColors,
                        onSave = { age, weightKg, invertColors ->
                            // launch() because save() is a suspend function —
                            // the click handler itself can't be suspend, so
                            // we hand the work off to a coroutine tied to
                            // this composition instead.
                            coroutineScope.launch {
                                userProfileRepository.save(UserProfile(age, weightKg, invertColors))
                            }
                            screen = Screen.Home
                        },
                        onCancel = { screen = Screen.Home },
                    )

                    is Screen.Active -> {
                        val now by rememberTickingNow()
                        val connectionState = service?.connectionState?.collectAsStateWithLifecycle(ConnectionState.Idle)?.value
                            ?: ConnectionState.Idle
                        val devices = service?.foundDevices?.collectAsStateWithLifecycle(emptyList())?.value ?: emptyList()
                        val heartRate = service?.heartRate?.collectAsStateWithLifecycle(null)?.value
                        val isPaused = service?.isPaused?.collectAsStateWithLifecycle(false)?.value ?: false
                        val elapsedSeconds = service?.elapsedSecondsAt(now) ?: 0L

                        ActiveWorkoutScreen(
                            workoutType = current.type,
                            connectionState = connectionState,
                            devices = devices,
                            heartRate = heartRate,
                            elapsedSeconds = elapsedSeconds,
                            maxHr = estimateMaxHeartRate(profile.age),
                            weightKg = profile.weightKg,
                            isPaused = isPaused,
                            onScanClick = {
                                if (hasPermissions()) beginTracking() else permissionLauncher.launch(requiredPermissions())
                            },
                            onDeviceClick = { service?.connectTo(it.device) },
                            onPauseResumeClick = { service?.togglePause() },
                            onEndClick = {
                                service?.endWorkout(profile.weightKg)
                                screen = Screen.Home
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}