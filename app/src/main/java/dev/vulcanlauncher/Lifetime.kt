package dev.vulcanlauncher

/**
 * Home's lifetime. Each rebuild ends one ([end]), because it replaces every view home had. Work deferred against home,
 * such as a posted runnable, a long-press or hold timer, an animator's update or end, or an async result, is made
 * through [bind] or checked with [token], so once a rebuild has happened it does nothing. It can't act on views that
 * are gone, or on the new ones in their place.
 */
class Lifetime {
    var generation = 0
        private set

    fun end() { generation++ }

    /** True while the lifetime this was taken in lasts. */
    fun token(): () -> Boolean { val g = generation; return { g == generation } }

    /** [block], doing nothing once the lifetime it was bound in has ended. */
    fun bind(block: () -> Unit): () -> Unit { val alive = token(); return { if (alive()) block() } }
}
