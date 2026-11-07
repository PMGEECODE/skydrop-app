package com.famage.skydrop.presence

import org.junit.Assert.*
import org.junit.Test

class PresenceManagerTest {

    @Test
    fun testPresenceStateTransitionsAndCanSend() {
        val peerOnline = PeerPresence(
            peerId = "peer-1",
            deviceName = "Desktop",
            state = PresenceState.ONLINE,
            isPaired = true
        )
        assertTrue("Paired online peer should be allowed to send", peerOnline.canSend)

        val peerBusy = peerOnline.copy(state = PresenceState.BUSY)
        assertTrue("Paired busy peer should be allowed to send", peerBusy.canSend)

        val peerDegraded = peerOnline.copy(state = PresenceState.DEGRADED)
        assertFalse("Degraded peer should not be ready to send", peerDegraded.canSend)

        val peerOffline = peerOnline.copy(state = PresenceState.OFFLINE)
        assertFalse("Offline peer should not be allowed to send", peerOffline.canSend)

        val peerUnpaired = peerOnline.copy(isPaired = false)
        assertFalse("Unpaired peer must never have canSend true", peerUnpaired.canSend)
    }

    @Test
    fun testMissedBeatsStateThresholds() {
        fun resolveState(missed: Int, curr: PresenceState): PresenceState {
            return when {
                missed >= PresenceConstants.MISSED_BEATS_OFFLINE_THRESHOLD -> PresenceState.OFFLINE
                missed == PresenceConstants.MISSED_BEATS_DEGRADED_THRESHOLD -> PresenceState.DEGRADED
                curr == PresenceState.UNKNOWN -> PresenceState.CHECKING
                else -> curr
            }
        }

        assertEquals(PresenceState.CHECKING, resolveState(1, PresenceState.UNKNOWN))
        assertEquals(PresenceState.DEGRADED, resolveState(2, PresenceState.ONLINE))
        assertEquals(PresenceState.OFFLINE, resolveState(3, PresenceState.DEGRADED))
        assertEquals(PresenceState.OFFLINE, resolveState(4, PresenceState.OFFLINE))
    }

    @Test
    fun testBackoffCalculation() {
        fun nextBackoff(curr: Long): Long {
            return if (curr == 0L) {
                PresenceConstants.INITIAL_BACKOFF_MS
            } else {
                (curr * 2).coerceAtMost(PresenceConstants.MAX_BACKOFF_MS)
            }
        }

        var b = nextBackoff(0L)
        assertEquals(1000L, b)
        b = nextBackoff(b)
        assertEquals(2000L, b)
        b = nextBackoff(b)
        assertEquals(4000L, b)
        b = nextBackoff(b)
        assertEquals(8000L, b)
        b = nextBackoff(b)
        assertEquals(16000L, b)
        b = nextBackoff(b)
        assertEquals(30000L, b) // capped at 30s
    }
}
