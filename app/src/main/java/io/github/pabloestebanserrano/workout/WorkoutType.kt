package io.github.pabloestebanserrano.workout

/**
 * `met` (Metabolic Equivalent of Task) drives the calorie estimate below.
 * These are reasonable general-purpose values, not measured for you
 * personally — treat calories as an estimate, not a precise reading.
 */
enum class WorkoutType(val displayName: String, val met: Double) {
    Walk("Walk", 3.5),
    Run("Run", 9.0),
    Core("Core", 4.0),
    Weightlifting("Weightlifting", 5.0),
    PunchingBag("Punching Bag", 7.5),
    Bike("Bike", 8.0),
    StationaryBike("Stationary Bike", 7.0),
}

/** Simple 5-zone model as a percentage of estimated max heart rate. */
enum class HeartRateZone(val label: String, val minFraction: Double) {
    Zone5("Zone 5 · Max", 0.90),
    Zone4("Zone 4 · Hard", 0.80),
    Zone3("Zone 3 · Moderate", 0.70),
    Zone2("Zone 2 · Light", 0.60),
    Zone1("Zone 1 · Warm-up", 0.50),
}

fun heartRateZoneFor(bpm: Int, maxHr: Int): HeartRateZone? {
    if (maxHr <= 0) return null
    val fraction = bpm.toDouble() / maxHr
    return HeartRateZone.entries.firstOrNull { fraction >= it.minFraction }
}

/** kcal = MET x bodyweight(kg) x duration(hours) — a standard estimate formula. */
fun estimateCalories(met: Double, weightKg: Double, elapsedSeconds: Long): Double =
    met * weightKg * (elapsedSeconds / 3600.0)

/** 220 - age is a coarse population estimate, fine until there's a real profile screen. DO: Find if there is a
 * better formula now that we have age and weight. */
fun estimateMaxHeartRate(age: Int): Int = 220 - age
