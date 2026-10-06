package kr.co.jumprope.camera.debug

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BundledFixtureTest {
    @Test fun bundledDemoProducesThreeEventsFromRawFrames() {
        File("src/main/assets/fixtures/basic-jumps.jsonl").bufferedReader().use {
            assertEquals(3, PoseLogCodec.replay(it.lineSequence()).jumpCount)
        }
    }
}
