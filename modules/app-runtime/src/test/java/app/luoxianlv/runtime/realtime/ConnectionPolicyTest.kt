package app.luoxianlv.runtime.realtime

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    @Test
    fun onlyOneAttemptAndOldNetworkCallbacksCannotReplaceCurrentConnection() {
        val policy = ConnectionPolicy { 1.0 }
        policy.availability(true, true, "wifi")
        val first = policy.begin(0)
        assertEquals(-1L, policy.begin(0))
        assertTrue(policy.availability(true, true, "mobile"))
        val second = policy.begin(10)
        assertFalse(policy.ready(first, 11))
        assertFalse(policy.failed(first, 11))
        assertTrue(policy.ready(second, 11))
    }

    @Test
    fun stopAndOfflinePreventReconnection() {
        val policy = ConnectionPolicy()
        policy.availability(true, true, "wifi")
        val attempt = policy.begin(0)
        policy.stop()
        assertEquals(-1L, policy.begin(100_000))
        assertFalse(policy.ready(attempt, 100_000))
        policy.availability(true, false, "")
        assertEquals(-1L, policy.begin(100_000))
        policy.availability(true, true, "wifi")
        assertTrue(policy.begin(100_000) > attempt)
    }

    @Test
    fun deadlineAndCooldownSurviveNetworkChange() {
        val policy = ConnectionPolicy { 1.0 }
        policy.availability(true, true, "wifi")
        val attempt = policy.begin(0)
        assertFalse(policy.timedOut(14_999))
        assertTrue(policy.timedOut(15_000))
        policy.failed(attempt, 15_000, 30_000)
        policy.availability(true, true, "mobile")
        assertEquals(-1L, policy.begin(44_999))
        assertTrue(policy.begin(45_000) > attempt)
    }

    @Test
    fun identityRejectionDoesNotLoopAndHealthySessionResetsBackoff() {
        val policy = ConnectionPolicy { 1.0 }
        policy.availability(true, true, "wifi")
        policy.failed(policy.begin(0), 0, rejected = true)
        assertEquals(-1L, policy.begin(60_000))
        policy.authorizedAgain()
        val attempt = policy.begin(60_000)
        policy.ready(attempt, 60_000)
        policy.failed(attempt, 120_001)
        assertEquals(-1L, policy.begin(121_000))
        assertTrue(policy.begin(121_001) > 0)
    }
}
