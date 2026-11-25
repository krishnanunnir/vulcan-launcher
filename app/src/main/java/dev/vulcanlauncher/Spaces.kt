package dev.vulcanlauncher

// Which kind of profile an app lives in, and where hidden and private apps may show.

enum class Profile { PERSONAL, WORK, PRIVATE, CLONE }

/**
 * The kind of a profile. [type] is Android's user type (LauncherUserInfo.userType, Android 15+; null before), [id] the
 * user id. A clone profile (Dual Messenger, Dual apps) holds second copies of personal apps: it isn't work.
 */
fun profileOf(own: Boolean, type: String?, id: Int): Profile = when {
    own -> Profile.PERSONAL
    type == "android.os.usertype.profile.PRIVATE" -> Profile.PRIVATE
    type == "android.os.usertype.profile.MANAGED" -> Profile.WORK
    // ponytail: before Android 15 nothing tells a launcher a profile's type, so clones are known by the ids their
    // makers use (Samsung 95-99, Xiaomi and OnePlus 999); another maker's clone still counts as work there.
    type == "android.os.usertype.profile.CLONE" || id in 95..99 || id == 999 -> Profile.CLONE
    else -> Profile.WORK
}

/** The app keys a list leaves out: the [hidden] ones, until the drawer's "Show hidden apps" reveals them. */
fun unlisted(hidden: Set<String>, showHidden: Boolean) = if (showHidden) emptySet() else hidden

/** Whether an app may be offered unasked (most used row, Now page, suggested folders): never a hidden or Private Space one. */
fun suggested(key: String, hidden: Set<String>, private: Boolean) = key !in hidden && !private
