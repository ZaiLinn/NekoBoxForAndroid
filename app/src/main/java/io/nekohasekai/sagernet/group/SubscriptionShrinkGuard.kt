package io.nekohasekai.sagernet.group

/**
 * Decides whether a subscription update looks incomplete, so that a temporary failure of the
 * provider (a truncated response, a partly broken list) does not delete the old proxies.
 *
 * Normal removals pass: an update that only deletes some proxies, or any update of a small
 * group, is applied as before.
 */
object SubscriptionShrinkGuard {

    /** Groups with fewer proxies than this are never guarded. */
    const val MIN_GUARDED = 5

    enum class Verdict {
        OK,

        /** The update has no proxies but the group has some. */
        EMPTY,

        /** Some proxy links failed to parse and old proxies would be deleted. */
        PARTIAL_PARSE,

        /** The update has less than half of the proxies the group has. */
        SHRINK,
    }

    /**
     * @param existing proxies in the group now
     * @param incoming proxies in the update, after deduplication
     * @param deleting old proxies the update would delete
     * @param invalidLinks proxy links of the update that failed to parse
     */
    fun check(existing: Int, incoming: Int, deleting: Int, invalidLinks: Int): Verdict = when {
        incoming == 0 -> if (existing > 0) Verdict.EMPTY else Verdict.OK
        deleting == 0 -> Verdict.OK
        invalidLinks > 0 -> Verdict.PARTIAL_PARSE
        existing >= MIN_GUARDED && incoming * 2 < existing -> Verdict.SHRINK
        else -> Verdict.OK
    }
}
