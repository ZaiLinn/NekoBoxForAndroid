package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdersTest {

    private class Item(val id: Long, var order: Long)

    /** ids 1..n with orders 10, 20, ... so a moved order is easy to see. */
    private fun items(n: Int) = (1..n).map { Item(it.toLong(), it * 10L) }.toMutableList()

    private fun MutableList<Item>.move(from: Int, to: Int) =
        moveKeepingOrder(from, to, { it.order }) { item, order -> item.order = order }

    private fun MutableList<Item>.ids() = map { it.id }

    /** What a reload from the database (ORDER BY userOrder) would show. */
    private fun MutableList<Item>.reloadedIds() = sortedBy { it.order }.map { it.id }

    private fun assertConsistent(list: MutableList<Item>, before: List<Long>) {
        assertEquals("ids lost or duplicated", before.sorted(), list.ids().sorted())
        assertEquals("orders not unique", list.size, list.map { it.order }.toSet().size)
        assertEquals("database order differs from the list", list.ids(), list.reloadedIds())
    }

    @Test
    fun moveDownOne() {
        val list = items(5)
        list.move(1, 2)
        assertEquals(listOf(1L, 3, 2, 4, 5), list.ids())
        assertConsistent(list, listOf(1, 2, 3, 4, 5))
    }

    @Test
    fun moveUpOne() {
        val list = items(5)
        list.move(2, 1)
        assertEquals(listOf(1L, 3, 2, 4, 5), list.ids())
        assertConsistent(list, listOf(1, 2, 3, 4, 5))
    }

    @Test
    fun moveDownSeveral() {
        val list = items(6)
        list.move(1, 4)
        assertEquals(listOf(1L, 3, 4, 5, 2, 6), list.ids())
        assertConsistent(list, listOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun moveUpSeveral() {
        // the old loop used `to + 1 downTo from`, an empty range here, so item 2 was overwritten
        val list = items(6)
        list.move(4, 1)
        assertEquals(listOf(1L, 5, 2, 3, 4, 6), list.ids())
        assertConsistent(list, listOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun moveLastToFirst() {
        val list = items(5)
        list.move(4, 0)
        assertEquals(listOf(5L, 1, 2, 3, 4), list.ids())
        assertConsistent(list, listOf(1, 2, 3, 4, 5))
    }

    @Test
    fun moveFirstToLast() {
        val list = items(5)
        list.move(0, 4)
        assertEquals(listOf(2L, 3, 4, 5, 1), list.ids())
        assertConsistent(list, listOf(1, 2, 3, 4, 5))
    }

    @Test
    fun returnsOnlyTheAffectedRange() {
        val list = items(6)
        val moved = list.move(4, 1)
        assertEquals(listOf(5L, 2, 3, 4), moved.map { it.id })
        // items outside the range keep their order
        assertEquals(10L, list[0].order)
        assertEquals(60L, list[5].order)
    }

    @Test
    fun repeatedStepsMatchOneJump() {
        // ItemTouchHelper may report a drag as several small moves or as one jump
        val steps = items(6)
        steps.move(5, 4)
        steps.move(4, 3)
        steps.move(3, 2)
        val jump = items(6)
        jump.move(5, 2)
        assertEquals(jump.ids(), steps.ids())
        assertEquals(jump.map { it.order }, steps.map { it.order })
        assertConsistent(steps, listOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun nonContiguousOrders() {
        val list = mutableListOf(Item(1, 3), Item(2, 7), Item(3, 8), Item(4, 100))
        list.move(3, 0)
        assertEquals(listOf(4L, 1, 2, 3), list.ids())
        assertEquals(listOf(3L, 7, 8, 100), list.map { it.order })
        assertConsistent(list, listOf(1, 2, 3, 4))
    }

    @Test
    fun noMoveCases() {
        val list = items(3)
        assertTrue(list.move(1, 1).isEmpty())
        // RecyclerView.NO_POSITION is -1
        assertTrue(list.move(-1, 1).isEmpty())
        assertTrue(list.move(1, -1).isEmpty())
        assertTrue(list.move(0, 3).isEmpty())
        assertEquals(listOf(1L, 2, 3), list.ids())
        assertEquals(listOf(10L, 20, 30), list.map { it.order })
    }

    @Test
    fun singleItem() {
        val list = items(1)
        assertTrue(list.move(0, 0).isEmpty())
        assertEquals(listOf(1L), list.ids())
    }
}
