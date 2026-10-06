package kr.co.jumprope.camera.session

import kr.co.jumprope.camera.detection.DetectorFixtures
import org.junit.Assert.*
import org.junit.Test

class WorkoutEngineTest {
    private fun engine() = WorkoutEngine("test", "2026-10-06T00:00:00Z", countdownMs = 0)
    private fun run(e: WorkoutEngine, from: Long, count: Int) {
        DetectorFixtures.cycles(from, count).forEach { e.frame(it) }
    }
    @Test fun countdownAndCalibrationPrecedeCounting() {
        val e = WorkoutEngine("t", "utc")
        DetectorFixtures.standing(duration = 2500).forEach { e.frame(it) }
        assertEquals(SessionPhase.COUNTDOWN, e.state.phase)
        DetectorFixtures.standing(3000).forEach { e.frame(it) }
        assertEquals(SessionPhase.COUNTING, e.state.phase)
    }
    @Test fun averageUsesValidTrackingAndZeroDenominatorIsNull() {
        assertNull(engine().finish(0, "end").averageJumpsPerMinute)
        val e = engine(); DetectorFixtures.standing().forEach { e.frame(it) }; run(e, 1530, 3)
        val s = e.finish(3800, "end")
        assertEquals(3, s.jumpCount)
        assertEquals(3 * 60_000.0 / s.validTrackingDurationMs, s.averageJumpsPerMinute!!, 0.001)
        assertTrue(s.activeDurationMs >= s.validTrackingDurationMs)
    }
    @Test fun pauseResumeExcludesTimeAndBreaksCadence() {
        val e = engine(); DetectorFixtures.standing().forEach { e.frame(it) }; run(e, 1530, 2)
        assertTrue(e.state.currentJumpsPerMinute > 0)
        e.pause(3200); val active = e.state.activeDurationMs; val valid = e.state.validTrackingDurationMs
        e.tick(8000); assertEquals(active, e.state.activeDurationMs); assertEquals(valid, e.state.validTrackingDurationMs)
        assertEquals(0.0, e.state.currentJumpsPerMinute, 0.001)
        e.resume(8000); DetectorFixtures.standing(8030).forEach { e.frame(it) }; run(e, 9560, 1)
        assertEquals(3, e.state.jumpCount); assertNull(e.state.lastJumpIntervalMs)
    }
    @Test fun stoppingAndTrackingGapClearCurrentCadence() {
        val e = engine(); DetectorFixtures.standing().forEach { e.frame(it) }; run(e, 1530, 2)
        e.tick(6000); assertEquals(0.0, e.state.currentJumpsPerMinute, 0.001)
        val before = e.state.validTrackingDurationMs
        e.frame(DetectorFixtures.frame(6033)); assertEquals(before, e.state.validTrackingDurationMs)
    }
    @Test fun delayedCallbacksRemainValidAndReverseFramesAreIgnored() {
        val e = engine(); e.tick(100)
        assertNotNull(e.frame(DetectorFixtures.frame(33)))
        assertNull(e.frame(DetectorFixtures.frame(33)))
        assertNull(e.frame(DetectorFixtures.frame(20)))
    }
    @Test fun pausedPartialCycleCannotCountAfterResume() {
        val e = engine(); DetectorFixtures.standing().forEach { e.frame(it) }
        e.frame(DetectorFixtures.frame(1530, 0.05f)); e.pause(1560); e.resume(2000)
        DetectorFixtures.standing(2030).forEach { e.frame(it) }
        assertEquals(0, e.state.jumpCount)
    }
    @Test fun processingDelayNeverMakesValidDurationLongerThanActiveDuration() {
        val e = engine()
        (DetectorFixtures.standing() + DetectorFixtures.cycles(1530, 2)).forEach {
            e.tick(it.timestampMs + 100)
            e.frame(it)
            assertTrue(e.state.activeDurationMs >= e.state.validTrackingDurationMs)
        }
    }
}
