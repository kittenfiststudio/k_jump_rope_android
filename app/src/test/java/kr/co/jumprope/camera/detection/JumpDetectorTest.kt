package kr.co.jumprope.camera.detection

import kr.co.jumprope.camera.pose.Joint
import org.junit.Assert.*
import org.junit.Test

class JumpDetectorTest {
    private fun calibrated(): JumpDetector = JumpDetector(sessionId = "test").also { d ->
        DetectorFixtures.standing().forEach(d::process)
        assertEquals(DetectorState.GROUND, d.snapshot.state)
    }
    @Test fun landingProducesExactlyOneEventPerCycle() {
        val d = calibrated()
        val events = DetectorFixtures.cycles(1530, 5).mapNotNull { d.process(it).event }
        assertEquals(5, events.size)
        assertEquals((1..5).toList(), events.map { it.sequence })
        assertEquals(5, events.map { it.eventId }.distinct().size)
        assertTrue(DetectorFixtures.standing(5200).none { d.process(it).event != null })
    }
    @Test fun tinyNoiseAndSquatsDoNotCount() {
        val noise = calibrated()
        assertTrue(DetectorFixtures.cycles(1530, 5, amplitude = 0.004f).none { noise.process(it).event != null })
        val squat = calibrated()
        val frames = (1530L..4500L step 33).map { t ->
            val amount = (kotlin.math.sin((t - 1530) / 300.0) + 1).toFloat() * 0.055f
            DetectorFixtures.frame(t, squat = amount)
        }
        assertTrue(frames.none { squat.process(it).event != null })
    }
    @Test fun visibleStationaryAnklesDoNotBlockHipCycles() {
        val d = calibrated()
        val events = DetectorFixtures.cycles(1530, 3).map { it.copy(landmarks = it.landmarks.mapValues { (j, p) ->
            if (j == Joint.LEFT_ANKLE || j == Joint.RIGHT_ANKLE) p?.copy(y = 0.86f) else p
        }) }.mapNotNull { d.process(it).event }
        assertEquals(3, events.size)
    }
    @Test fun loweredAnkleReferenceDoesNotBlockHipCycles() {
        val d = calibrated()
        val events = DetectorFixtures.cycles(1530, 3).map { frame ->
            frame.copy(landmarks = frame.landmarks.mapValues { (joint, point) ->
                if (joint == Joint.LEFT_ANKLE || joint == Joint.RIGHT_ANKLE) point?.copy(y = 0.90f) else point
            })
        }.mapNotNull { d.process(it).event }
        assertEquals(3, events.size)
    }
    @Test fun smallHipCyclesAtCameraProcessingRateCountWithStationaryAnkles() {
        val d = calibrated()
        assertEquals(5, DetectorFixtures.cycles(1530, 5, amplitude = 0.007f, step = 63,
            period = 500, airborne = 250).map { frame ->
                frame.copy(landmarks = frame.landmarks.mapValues { (joint, point) ->
                    if (joint == Joint.LEFT_ANKLE || joint == Joint.RIGHT_ANKLE) point?.copy(y = 0.86f) else point
                })
            }.mapNotNull { d.process(it).event }.size)
    }
    @Test fun missingAnkleStillDiscardsCycle() {
        val d = calibrated()
        d.process(DetectorFixtures.frame(1530, 0.04f))
        val missing = DetectorFixtures.frame(1563, 0.06f).let { it.copy(landmarks = it.landmarks + (Joint.LEFT_ANKLE to null)) }
        assertNull(d.process(missing).event)
        assertEquals(DetectorState.LOST, d.snapshot.state)
        assertTrue(DetectorFixtures.standing(1629).none { d.process(it).event != null })
    }
    @Test fun missingJointDiscardsIncompleteCycle() {
        val d = calibrated()
        d.process(DetectorFixtures.frame(1530, 0.04f)); d.process(DetectorFixtures.frame(1563, 0.06f))
        d.process(DetectorFixtures.frame(1596).copy(landmarks = emptyMap()))
        assertEquals(DetectorState.LOST, d.snapshot.state)
        assertTrue(DetectorFixtures.standing(1629).none { d.process(it).event != null })
    }
    @Test fun duplicateAndReverseTimestampsCannotEmitEvents() {
        val d = calibrated()
        assertTrue(d.process(DetectorFixtures.frame(1000, 0.1f)).ignoredTimestamp)
        assertTrue(d.process(DetectorFixtures.frame(1485, 0.1f)).ignoredTimestamp)
    }
    @Test fun gapAndPositionJumpDiscardCycle() {
        val d = calibrated()
        d.process(DetectorFixtures.frame(1530, 0.05f))
        assertNull(d.process(DetectorFixtures.frame(2200)).event)
        assertEquals(DetectorState.CALIBRATING, d.snapshot.state)
        DetectorFixtures.standing(2233).forEach(d::process)
        assertNull(d.process(DetectorFixtures.frame(3800, offsetX = 0.3f)).event)
        assertEquals(DetectorState.CALIBRATING, d.snapshot.state)
    }
    @Test fun incompleteOrTimedOutCycleDoesNotCount() {
        val d = calibrated()
        (1530L..3600L step 33).forEach { assertNull(d.process(DetectorFixtures.frame(it, 0.05f)).event) }
        assertTrue(DetectorFixtures.standing(3633).none { d.process(it).event != null })
    }
    @Test fun irregularFpsAndDeterministicReplay() {
        val frames = DetectorFixtures.standing() + DetectorFixtures.cycles(1530, 4)
            .filterIndexed { index, _ -> index % 5 != 0 }
        fun events() = JumpDetector(sessionId = "same").let { d -> frames.mapNotNull { d.process(it).event } }
        assertEquals(4, events().size)
        assertEquals(events(), events())
    }
    @Test fun sessionResetAndFirstIrregularJump() {
        val frames = DetectorFixtures.standing() + DetectorFixtures.cycles(1530, 1)
        val a = JumpDetector(sessionId = "a"); val b = JumpDetector(sessionId = "b")
        val ea = frames.mapNotNull { a.process(it).event }.single()
        val eb = frames.mapNotNull { b.process(it).event }.single()
        assertEquals(1, ea.sequence); assertEquals(1, eb.sequence); assertNotEquals(ea.eventId, eb.eventId)
    }
    @Test fun smallerAndFasterSyntheticJumpsStillCount() {
        val small = calibrated()
        assertEquals(3, DetectorFixtures.cycles(1530, 3, amplitude = 0.035f)
            .mapNotNull { small.process(it).event }.size)
        val fast = calibrated()
        assertEquals(5, DetectorFixtures.cycles(1530, 5, amplitude = 0.05f, step = 22,
            period = 300, airborne = 150).mapNotNull { fast.process(it).event }.size)
    }
}
