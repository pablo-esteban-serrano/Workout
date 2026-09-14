package io.github.pabloestebanserrano.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ticks once a second — used purely to trigger recomposition of the elapsed-time display. */
@Composable
fun rememberTickingNow(): State<Long> {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.value = System.currentTimeMillis()
            delay(1000)
        }
    }
    return now
}

@Composable
fun HomeScreen(onSelectWorkout: (WorkoutType) -> Unit, onOpenSettings: () -> Unit, onOpenHistory: () -> Unit) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // statusBarsPadding() insets by however tall the system status
            // bar/clock actually is on *this* device, rather than a fixed
            // dp guess — the Light Phone III and the Mudita Kompakt don't
            // necessarily reserve the same amount of space up top.
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Workout", style = typography.title, color = colors.content)
            // Two links stacked in a column rather than spread across the
            // row — the title is already the widest thing on this line at
            // 64sp, so a second Row here would either wrap awkwardly or
            // force the title smaller. Stacking keeps both reachable
            // without touching the title's size.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "HISTORY",
                    style = typography.fine,
                    color = colors.contentSecondary,
                    modifier = Modifier.clickable(onClick = onOpenHistory).padding(8.dp),
                )
                Text(
                    text = "SETTINGS",
                    style = typography.fine,
                    color = colors.contentSecondary,
                    modifier = Modifier.clickable(onClick = onOpenSettings).padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        LazyColumn {
            items(WorkoutType.entries) { type ->
                Text(
                    text = type.displayName,
                    style = typography.heading,
                    color = colors.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectWorkout(type) }
                        .padding(vertical = 14.dp),
                )
                HorizontalDivider(color = colors.contentSecondary.copy(alpha = 0.25f))
            }
        }
    }
}

@Composable
fun ActiveWorkoutScreen(
    workoutType: WorkoutType,
    connectionState: ConnectionState,
    devices: List<FoundDevice>,
    heartRate: Int?,
    elapsedSeconds: Long,
    maxHr: Int,
    weightKg: Double,
    isPaused: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (FoundDevice) -> Unit,
    onPauseResumeClick: () -> Unit,
    onEndClick: () -> Unit,
) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current
    val zone = heartRate?.let { heartRateZoneFor(it, maxHr) }
    val calories = estimateCalories(workoutType.met, weightKg, elapsedSeconds)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // statusBarsPadding() clears the system clock first; the extra
            // top padding on top of that is because the workout type name
            // (small, right at the top) was the one header actually getting
            // covered, so it gets a bit more clearance than the other
            // screens' padding buys them by default.
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(workoutType.displayName, style = typography.heading, color = colors.contentSecondary)
        Spacer(Modifier.height(8.dp))
        Text(formatElapsed(elapsedSeconds), style = typography.title, color = colors.content)

        Spacer(Modifier.height(35.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(45.dp)) {
            StatColumn("HR", heartRate?.toString() ?: "--")
            StatColumn("ZONE", zone?.label?.substringBefore(" ·") ?: "--")
            StatColumn("CAL", calories.roundToInt().toString())
        }

        Spacer(Modifier.weight(1f))

        when (connectionState) {
            is ConnectionState.Connected -> {
                Text("Connected: ${connectionState.deviceName}", style = typography.fine, color = colors.contentSecondary)
            }
            is ConnectionState.Reconnecting -> {
                // Plain text, not a button — there's nothing for the person
                // to tap here, this is happening automatically in the
                // background. A "Retry" button would wrongly suggest the
                // retry wasn't already in progress.
                Text("Reconnecting…", style = typography.fine, color = colors.contentSecondary)
            }
            is ConnectionState.Error -> {
                Text(connectionState.message, style = typography.fine, color = colors.contentSecondary)
                LightTextButton("Retry", onScanClick)
            }
            else -> {
                LightTextButton(if (connectionState == ConnectionState.Scanning) "Scanning…" else "Find HR monitor", onScanClick)
                Spacer(Modifier.height(8.dp))
                devices.forEach { device ->
                    Text(
                        device.name,
                        style = typography.detail,
                        color = colors.content,
                        modifier = Modifier.clickable { onDeviceClick(device) }.padding(vertical = 10.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LightTextButton(if (isPaused) "Resume" else "Pause", onPauseResumeClick)
            LightTextButton("End", onEndClick)
        }
    }
}
/**
 * Lets the person enter their real age and weight, which flow into the
 * calorie estimate (kcal = MET x weight x hours) and max-HR estimate
 * (220 - age) instead of the hardcoded placeholders that were here before.
 *
 * `initialAge`/`initialWeightKg` come from whatever's currently saved (or
 * the repository's default, before anything's been saved yet) — this
 * screen doesn't talk to storage directly, it just reports the final
 * numbers back up via `onSave` and lets MainActivity decide what to do
 * with them. Keeping storage out of the Composable is what makes this
 * screen trivial to preview/test in isolation later if you want to.
 */
@Composable
fun SettingsScreen(
    initialAge: Int,
    initialWeightKg: Double,
    initialInvertColors: Boolean,
    onSave: (age: Int, weightKg: Double, invertColors: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current

    // Text fields work in String, not Int/Double — we keep raw typed text
    // here and only parse it into real numbers on Save. Parsing on every
    // keystroke instead would fight the keyboard: an empty field, or "7."
    // while still typing "7.5", would either crash a naive toInt()/toDouble()
    // call or force awkward "is this even a valid number yet" logic on
    // every single character typed.
    var ageText by remember { mutableStateOf(initialAge.toString()) }
    var weightText by remember { mutableStateOf(initialWeightKg.toString()) }
    // Not tied to DataStore directly (same separation as ageText/weightText
    // above) — just a local toggle that gets reported back via onSave.
    var invertColors by remember { mutableStateOf(initialInvertColors) }

    // The hardware/gesture back action normally just closes the Activity
    // when there's nothing else to pop — BackHandler intercepts it here so
    // "back" always means "leave Settings" instead, giving a way out that
    // doesn't depend on the Cancel button being visible or reachable at all
    // (which matters on LightOS, see the imePadding/scroll notes below).
    BackHandler(onBack = onCancel)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            // imePadding() adds bottom padding equal to however much of the
            // screen the on-screen keyboard is currently covering, and that
            // padding shrinks/grows live as the keyboard animates in and
            // out. Without it, content just sits where it always sits and
            // whatever the keyboard happens to overlap gets covered — which
            // is exactly what was happening on the Light Phone. Needs to be
            // *inside* fillMaxSize() (applied to this Column, not a parent)
            // so it affects layout rather than just clipping.
            .imePadding()
            // verticalScroll makes the column scrollable when its content is
            // taller than the visible space — which is now often the case
            // once imePadding() has eaten into that space. Together these
            // two guarantee every field and both buttons are always
            // reachable by scrolling, regardless of screen size or how a
            // given OS chooses to resize/pan around the keyboard.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Text("Settings", style = typography.title, color = colors.content)
        Spacer(Modifier.height(24.dp))

        Text("AGE", style = typography.fine, color = colors.contentSecondary)
        Spacer(Modifier.height(2.dp))
        LightNumberField(value = ageText, onValueChange = { ageText = it })

        Spacer(Modifier.height(8.dp))

        Text("WEIGHT (KG)", style = typography.fine, color = colors.contentSecondary)
        Spacer(Modifier.height(2.dp))
        LightNumberField(value = weightText, onValueChange = { weightText = it })

        Spacer(Modifier.height(8.dp))

        Text("DISPLAY", style = typography.fine, color = colors.contentSecondary)
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A plain tap-to-toggle text label rather than a Material
            // Switch, same reasoning as the DELETE/CONFIRM row in
            // WorkoutHistoryScreen: a default Switch pulls in Material3's
            // rounded, colored default styling, which clashes with this
            // app's plain black/white text-button language.
            Text(
                text = "Invert colors",
                style = typography.detail,
                color = colors.content,
            )
            Text(
                text = if (invertColors) "ON" else "OFF",
                style = typography.button,
                color = colors.content,
                modifier = Modifier.clickable { invertColors = !invertColors }.padding(8.dp),
            )
        }

        // A fixed spacer instead of weight(1f): weight() needs a bounded
        // parent height to divide up, but verticalScroll() measures this
        // Column with effectively unbounded height so it can grow past the
        // screen — mixing the two would crash at runtime. Scrolling is what
        // guarantees the buttons stay reachable now, not this spacer.
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LightTextButton("Cancel", onCancel)
            LightTextButton("Save") {
                // toIntOrNull()/toDoubleOrNull() return null instead of
                // throwing on bad input (an emptied field, stray letters,
                // etc.) — falling back to the last-known-good value means a
                // fumbled edit can't leave the profile in a broken state.
                val age = ageText.toIntOrNull() ?: initialAge
                val weight = weightText.toDoubleOrNull() ?: initialWeightKg
                onSave(age, weight, invertColors)
            }
        }
    }
}

/**
 * Read-only list of past workouts, newest first (the DAO's ORDER BY already
 * guarantees that — this screen just displays whatever order it receives).
 * `records` is collected from Room's Flow up in MainActivity and passed in
 * here as a plain List, keeping this Composable free of any database
 * knowledge, same separation SettingsScreen keeps from DataStore.
 */
@Composable
fun WorkoutHistoryScreen(records: List<WorkoutRecord>, onBack: () -> Unit, onDelete: (WorkoutRecord) -> Unit) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current

    // Same reasoning as SettingsScreen's BackHandler: there's no bottom nav
    // here, so system back needs to be the guaranteed way out.
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History", style = typography.title, color = colors.content)
            Text(
                text = "BACK",
                style = typography.fine,
                color = colors.contentSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        if (records.isEmpty()) {
            // No LazyColumn needed for an empty state — avoids an empty
            // scrollable area that looks like something failed to load.
            Text("No workouts yet", style = typography.detail, color = colors.contentSecondary)
        } else {
            LazyColumn {
                // `key = { it.id }` tells Compose how to tell rows apart
                // across recompositions (e.g. a new workout getting
                // inserted at the top) so it can reuse/animate existing rows
                // instead of treating the whole list as brand new every time.
                items(records, key = { it.id }) { record ->
                    WorkoutHistoryRow(record, onDelete = onDelete)
                    HorizontalDivider(color = colors.contentSecondary.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun WorkoutHistoryRow(record: WorkoutRecord, onDelete: (WorkoutRecord) -> Unit) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current

    // Two-tap confirm instead of deleting on the first tap, and instead of a
    // system AlertDialog: a dialog would pull in Material3's default light,
    // rounded-corner styling, which would clash with the plain black/white
    // text-button language this whole app already uses (SETTINGS, BACK,
    // Cancel/Save). "armed" is local to this one row — arming one row's
    // delete doesn't affect any other row.
    var armed by remember { mutableStateOf(false) }

    // If the person taps DELETE once, then scrolls away or gets distracted
    // instead of confirming, this un-arms it after a few seconds rather than
    // leaving it silently primed — otherwise a much later, unrelated tap in
    // roughly the same spot could delete something they never meant to touch.
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(record.workoutType.displayName, style = typography.heading, color = colors.content)
            Text(formatElapsed(record.durationSeconds), style = typography.heading, color = colors.content)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(formatWorkoutDate(record.startedAtEpochMs), style = typography.fine, color = colors.contentSecondary)
                val hrPart = record.averageHeartRate?.let { avg -> "avg $avg · peak ${record.peakHeartRate ?: avg} bpm" }
                    ?: "no HR data"
                Text("${record.calories.roundToInt()} cal · $hrPart", style = typography.fine, color = colors.contentSecondary)
            }
            Text(
                text = if (armed) "CONFIRM?" else "DELETE",
                style = typography.fine,
                // Brighter (full Content, not ContentSecondary) while armed —
                // a plain color change rather than a red/warning accent,
                // consistent with this theme's deliberate no-accent-color
                // palette, but still a visible state change on tap.
                color = if (armed) colors.content else colors.contentSecondary,
                modifier = Modifier
                    .clickable {
                        if (armed) {
                            onDelete(record)
                            armed = false
                        } else {
                            armed = true
                        }
                    }
                    .padding(8.dp),
            )
        }
    }
}

private fun formatWorkoutDate(epochMs: Long): String =
    // SimpleDateFormat over java.time here purely for simplicity — it needs
    // no extra desugaring setup to work across every Android version this
    // app might run on, and a workout list doesn't need java.time's extra
    // precision/timezone handling to just show "Sep 8, 3:41 PM".
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMs))

/** A TextField restyled to match the app's black/white palette and typography. */
@Composable
private fun LightNumberField(value: String, onValueChange: (String) -> Unit) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = typography.heading.copy(color = colors.content),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.background,
            unfocusedContainerColor = colors.background,
            focusedIndicatorColor = colors.content,
            unfocusedIndicatorColor = colors.contentSecondary,
            cursorColor = colors.content,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatColumn(label: String, value: String) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = typography.heading, color = colors.content)
        Text(label, style = typography.fine, color = colors.contentSecondary)
    }
}

@Composable
private fun LightTextButton(label: String, onClick: () -> Unit) {
    val typography = LocalLightTypography.current
    val colors = LocalLightColors.current
    Text(
        text = label.uppercase(),
        style = typography.button,
        color = colors.content,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
