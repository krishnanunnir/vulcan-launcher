package dev.vulcanlauncher

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

// The one owner of profiles (work, Private Space, clones) and hidden apps: what kind each profile is, whether it is
// on, and where an app may show. Lists ask here instead of deciding for themselves.

/** CLONE: any second space that isn't work or private. Its apps show with the personal ones and it is never paused. */
enum class Profile { PERSONAL, WORK, PRIVATE, CLONE }

/** OFF: paused (work) or locked (Private Space). CHANGING: switched on, its storage not unlocked yet. */
enum class SpaceState { ON, OFF, CHANGING }

/** Where an app may show: the drawer's lists, search results, and what Vulcan offers unasked (most used, Now page, suggested folders). */
enum class Surface { DRAWER, SEARCH, SUGGEST }

/** What asking Android to pause or resume came to, best first. ASKED: Android shows its own unlock prompt. */
enum class Outcome { DONE, NOT_HOME, FAILED, ASKED }

/**
 * The kind of a profile. [type] is Android's user type (LauncherUserInfo.userType, Android 15+; null before), [id] the
 * user id. A clone profile (Dual Messenger, Dual apps) holds second copies of personal apps: it isn't work.
 */
fun profileOf(own: Boolean, type: String?, id: Int): Profile = when {
    own -> Profile.PERSONAL
    type == "android.os.usertype.profile.PRIVATE" -> Profile.PRIVATE
    type == "android.os.usertype.profile.MANAGED" -> Profile.WORK
    // Android named a type and it isn't work: never guessed to be (#130).
    type != null -> Profile.CLONE
    // ponytail: before Android 15 nothing tells a launcher a profile's type, so clones are known by the ids their
    // makers use (Samsung 95-99, Xiaomi and OnePlus 999); another maker's clone counts as work until Android refuses
    // to pause it ([Spaces.toggleWork]).
    id in 95..99 || id == 999 -> Profile.CLONE
    else -> Profile.WORK
}

fun spaceState(quiet: Boolean, unlocked: Boolean) = if (quiet) SpaceState.OFF else if (!unlocked) SpaceState.CHANGING else SpaceState.ON

/**
 * Which way the work switch goes and for which profiles: pause while any is on, and only those not already as wanted,
 * so one press never pauses one profile and resumes another. Null: no work profile.
 */
fun <U> workSwitch(states: Map<U, SpaceState>): Pair<Boolean, List<U>>? {
    if (states.isEmpty()) return null
    val pause = states.values.any { it != SpaceState.OFF }
    return pause to states.filterValues { (it != SpaceState.OFF) == pause }.keys.toList()
}

/**
 * Whether an app shows on [surface]. [hidden]: the user hid it; [reveal]: the drawer's "Show hidden apps" is on.
 * Home shows every loaded app, and a locked Private Space's apps aren't loaded at all, so neither is asked here.
 */
fun shows(surface: Surface, kind: Profile, hidden: Boolean, reveal: Boolean = false) = when (surface) {
    Surface.SUGGEST -> !hidden && kind != Profile.PRIVATE
    Surface.DRAWER, Surface.SEARCH -> !hidden || reveal
}

class Spaces(private val um: UserManager, private val launcherApps: LauncherApps, private val store: Store) {
    private var kinds = emptyMap<UserHandle, Profile>()
    private var states = emptyMap<UserHandle, SpaceState>()
    // ponytail: kept in memory only, so after a restart the first press of the work switch learns it again.
    private val refused = HashSet<UserHandle>()

    /** Reads every profile's kind and state; [AppRepo.loadApps] calls it, so both are as of the app list on screen. */
    fun load(profiles: List<UserHandle>) {
        // hashCode is the user id: UserHandle has no public getter for it.
        kinds = profiles.associateWith { u ->
            if (u in refused) Profile.CLONE else profileOf(u == Process.myUserHandle(),
                if (Build.VERSION.SDK_INT >= 35) runCatching { launcherApps.getLauncherUserInfo(u)?.userType }.getOrNull() else null, u.hashCode())
        }
        states = profiles.associateWith(::stateNow)
    }

    /** Asked of Android now, for a profile [load] may not have seen. */
    fun stateNow(u: UserHandle) = spaceState(um.isQuietModeEnabled(u), um.isUserUnlocked(u))
    fun kind(u: UserHandle) = kinds[u] ?: Profile.PERSONAL
    fun state(u: UserHandle) = states[u] ?: SpaceState.ON

    /** The Private Space profile (Android 15+), or null. Android lists it only to the home app holding ACCESS_HIDDEN_PROFILES. */
    val privateUser get() = kinds.entries.firstOrNull { it.value == Profile.PRIVATE }?.key
    /** A locked Private Space shows none of its apps, anywhere. */
    val privateLocked get() = privateUser?.let { state(it) == SpaceState.OFF } ?: false
    val work get() = kinds.filterValues { it == Profile.WORK }.keys
    fun isWork(a: App) = kind(a.info.user) == Profile.WORK
    fun isPrivate(a: App) = kind(a.info.user) == Profile.PRIVATE

    /**
     * Pauses or resumes the work profiles; Android lets the default home app do this (API 28+). Returns which way it
     * went and how it came out, or null when there is no work profile to switch. The profile broadcast reloads the apps.
     */
    fun toggleWork(): Pair<Boolean, Outcome>? {
        if (Build.VERSION.SDK_INT < 28) return null
        val (pause, users) = workSwitch(work.associateWith(::stateNow)) ?: return null
        return pause to users.map { u ->
            try {
                // Pausing never asks for anything: a "no" means this profile can't be paused.
                if (um.requestQuietModeEnabled(pause, u)) Outcome.DONE else if (pause) refuse(u) else Outcome.ASKED
            } catch (e: SecurityException) { Outcome.NOT_HOME } catch (e: Exception) { refuse(u) }
        }.minBy { it.ordinal }
    }

    /** A profile Android won't pause isn't work: left counted as one, it kept the switch on "pause" for good (#130). */
    private fun refuse(u: UserHandle): Outcome { refused += u; kinds = kinds + (u to Profile.CLONE); return Outcome.FAILED }

    /** Locks or unlocks Private Space; unlocking asks for the screen lock first. The profile broadcast then reloads the apps. */
    fun setPrivateLocked(lock: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) privateUser?.let { runCatching { um.requestQuietModeEnabled(lock, it) } }
    }

    /** The keys of the apps the user hid. Read once per list, not per app. */
    val hidden: MutableSet<String> get() = store.strings("hidden")
    fun setHidden(keys: Collection<String>) = store.putStrings("hidden", keys)
    fun shows(a: App, surface: Surface, hidden: Set<String>, reveal: Boolean = false) = shows(surface, kind(a.info.user), a.key in hidden, reveal)
}
