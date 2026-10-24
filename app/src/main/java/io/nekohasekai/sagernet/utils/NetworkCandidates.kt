package io.nekohasekai.sagernet.utils

/** Tracks all available networks when Android reports each match instead of only the best one. */
internal class NetworkCandidates<N : Any, L : Any> {
    private val available = LinkedHashMap<N, L?>()

    var current: N? = null
        private set

    val currentLink: L?
        get() = current?.let { available[it] }

    fun onAvailable(network: N, multiple: Boolean) {
        if (!multiple) available.clear()
        available.remove(network)
        available[network] = null
        current = network
    }

    /** Returns whether the selected network was updated. */
    fun onUpdate(network: N, link: L?): Boolean {
        if (!available.containsKey(network)) return false
        if (link != null) available[network] = link
        return current == network
    }

    /** Returns whether the selected network changed. */
    fun onLost(network: N): Boolean {
        if (!available.containsKey(network)) return false
        available.remove(network)
        if (current != network) return false
        current = available.keys.lastOrNull()
        return true
    }

    fun clear() {
        available.clear()
        current = null
    }
}
