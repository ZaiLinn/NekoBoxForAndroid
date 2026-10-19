package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.group.SubscriptionShrinkGuard.Verdict
import io.nekohasekai.sagernet.group.SubscriptionShrinkGuard.check
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionShrinkGuardTest {

    @Test
    fun normalUpdate() {
        assertEquals(Verdict.OK, check(existing = 20, incoming = 21, deleting = 0, invalidLinks = 0))
    }

    @Test
    fun firstUpdate() {
        assertEquals(Verdict.OK, check(existing = 0, incoming = 30, deleting = 0, invalidLinks = 0))
    }

    @Test
    fun providerRemovesSome() {
        assertEquals(Verdict.OK, check(existing = 20, incoming = 15, deleting = 5, invalidLinks = 0))
        // exactly half is still a normal removal
        assertEquals(Verdict.OK, check(existing = 20, incoming = 10, deleting = 10, invalidLinks = 0))
    }

    @Test
    fun providerRenamesAll() {
        // a rename deletes every old proxy but keeps the count
        assertEquals(Verdict.OK, check(existing = 20, incoming = 20, deleting = 20, invalidLinks = 0))
    }

    @Test
    fun emptySubscription() {
        assertEquals(Verdict.EMPTY, check(existing = 20, incoming = 0, deleting = 20, invalidLinks = 0))
        assertEquals(Verdict.EMPTY, check(existing = 1, incoming = 0, deleting = 1, invalidLinks = 0))
        assertEquals(Verdict.OK, check(existing = 0, incoming = 0, deleting = 0, invalidLinks = 0))
    }

    @Test
    fun parseFailure() {
        assertEquals(
            Verdict.PARTIAL_PARSE, check(existing = 20, incoming = 18, deleting = 2, invalidLinks = 2)
        )
        // nothing would be deleted, so a partly broken list only adds and updates
        assertEquals(Verdict.OK, check(existing = 20, incoming = 22, deleting = 0, invalidLinks = 3))
    }

    @Test
    fun abnormalDrop() {
        assertEquals(Verdict.SHRINK, check(existing = 20, incoming = 3, deleting = 17, invalidLinks = 0))
        assertEquals(Verdict.SHRINK, check(existing = 5, incoming = 2, deleting = 3, invalidLinks = 0))
    }

    @Test
    fun smallGroupIsNotGuarded() {
        assertEquals(Verdict.OK, check(existing = 4, incoming = 1, deleting = 3, invalidLinks = 0))
    }
}
