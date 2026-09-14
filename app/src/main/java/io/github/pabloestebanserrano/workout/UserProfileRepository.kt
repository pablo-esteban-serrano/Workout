package io.github.pabloestebanserrano.workout

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * This is an "extension property" — Kotlin lets you bolt a new property onto
 * a class you don't own (here, Android's Context) instead of subclassing it.
 * `by preferencesDataStore(...)` is a *property delegate*: Android's own
 * library provides the getter logic, and it guarantees only ONE DataStore
 * instance ever gets created for this file name per process, no matter how
 * many times this property is accessed. That single-instance guarantee
 * matters for DataStore specifically — creating two instances pointed at the
 * same file is a documented way to corrupt it.
 *
 * "private" here just means only this file needs to reach the DataStore
 * directly; everything else goes through the repository below instead.
 */
private val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile")

/**
 * The personal info and display preferences the app currently needs, bundled
 * together. `invertColors` is a display preference rather than "personal
 * info" strictly speaking, but it lives here anyway rather than in a second
 * DataStore file — one small settings blob is simpler than two, and nothing
 * about this app needs them to vary independently or be saved separately.
 */
data class UserProfile(val age: Int, val weightKg: Double, val invertColors: Boolean = false)

/**
 * Repository = a small class whose only job is to hide *where* data comes
 * from behind a simple API. Right now that's DataStore; if you ever moved to
 * a server sync or a different storage mechanism, only this file would need
 * to change — MainActivity and the screens wouldn't know the difference.
 */
class UserProfileRepository(private val context: Context) {

    companion object {
        // Exposed so MainActivity has a sensible starting value to show
        // *before* the real saved value has loaded off disk (DataStore reads
        // are async — there's always a brief moment with nothing to show yet).
        val DEFAULT_PROFILE = UserProfile(age = 30, weightKg = 70.0, invertColors = false)
    }

    // DataStore stores everything under string "keys" rather than named
    // fields — these two objects are just typed handles for "age" and
    // "weight_kg" so the rest of the code can't typo a raw string key.
    private object Keys {
        val AGE = intPreferencesKey("age")
        val WEIGHT_KG = doublePreferencesKey("weight_kg")
        val INVERT_COLORS = booleanPreferencesKey("invert_colors")
    }

    /**
     * A cold Flow of the current profile. "Cold" means it does nothing until
     * something collects it — no background polling. Once collected, it
     * automatically emits a fresh UserProfile every time the underlying file
     * changes, including changes made via save() from somewhere else
     * entirely (e.g. the Settings screen updates it, and MainActivity's
     * collector — already running for the Active screen's calorie math —
     * picks up the new value on its own, no manual refresh needed).
     */
    val profile: Flow<UserProfile> = context.userProfileDataStore.data.map { prefs ->
        UserProfile(
            age = prefs[Keys.AGE] ?: DEFAULT_PROFILE.age,
            weightKg = prefs[Keys.WEIGHT_KG] ?: DEFAULT_PROFILE.weightKg,
            invertColors = prefs[Keys.INVERT_COLORS] ?: DEFAULT_PROFILE.invertColors,
        )
    }

    /**
     * `suspend` because writing to disk is I/O and shouldn't block the UI
     * thread — callers need a coroutine scope to call this (MainActivity
     * will use `rememberCoroutineScope()` for that, same pattern you'd use
     * for any other suspend call from a Compose click handler).
     */
    suspend fun save(profile: UserProfile) {
        context.userProfileDataStore.edit { prefs ->
            prefs[Keys.AGE] = profile.age
            prefs[Keys.WEIGHT_KG] = profile.weightKg
            prefs[Keys.INVERT_COLORS] = profile.invertColors
        }
    }
}
