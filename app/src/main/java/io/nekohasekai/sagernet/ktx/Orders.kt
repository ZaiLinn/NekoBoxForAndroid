package io.nekohasekai.sagernet.ktx

/**
 * Moves the item at [from] to [to] and shifts the items in between by one position, as a drag
 * in a list sorted by user order does.
 *
 * The order values of the affected range are kept in ascending order and given to the items in
 * their new positions, so the list stays sorted by user order and no value is lost or duplicated.
 *
 * @return the items in the affected range, all of which may have a new order, or an empty list
 * if nothing moved.
 */
fun <T> MutableList<T>.moveKeepingOrder(
    from: Int,
    to: Int,
    getOrder: (T) -> Long,
    setOrder: (T, Long) -> Unit,
): List<T> {
    if (from == to || from !in indices || to !in indices) return emptyList()
    val low = minOf(from, to)
    val high = maxOf(from, to)
    val orders = (low..high).map { getOrder(this[it]) }.sorted()
    add(to, removeAt(from))
    return (low..high).map { index ->
        this[index].also { setOrder(it, orders[index - low]) }
    }
}
