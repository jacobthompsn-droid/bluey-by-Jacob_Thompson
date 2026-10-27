package com.jacob.bluey.face

import com.jacob.bluey.link.FaceState
import com.jacob.bluey.link.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FaceAnimatorTest {
    private fun run(animator: FaceAnimator, from: Double, seconds: Double): FaceAnimator.Frame {
        var t = from
        var frame = animator.step(t)
        while (t < from + seconds) {
            t += 1.0 / 60
            frame = animator.step(t)
        }
        return frame
    }

    @Test
    fun eyesSettleWhereTheLaptopLooks() {
        val animator = FaceAnimator(Random(1))
        animator.receive(FaceState(gazeX = -0.8, gazeY = -0.3, mood = Mood.LISTENING), 0.0)
        // Keep packets coming, as the laptop does at least once a second.
        var frame = run(animator, 0.0, 1.0)
        animator.receive(FaceState(gazeX = -0.8, gazeY = -0.3, mood = Mood.LISTENING), 1.0)
        frame = run(animator, 1.0, 1.0)
        assertEquals(-0.8, frame.gaze.x, 0.05)
        assertEquals(-0.3, frame.gaze.y, 0.05)
        assertEquals(Mood.LISTENING, frame.mood)
    }

    @Test
    fun takesTheLaptopsMoodThenFallsBackWhenItGoesQuiet() {
        val animator = FaceAnimator(Random(2))
        animator.receive(FaceState(mood = Mood.SLEEPY), 0.0)
        assertEquals(Mood.SLEEPY, run(animator, 0.0, 1.0).mood)
        assertEquals(Mood.LISTENING, run(animator, 1.0, 3.0).mood)  // no packet for 2.5 s
    }

    @Test
    fun everyMoodProducesSaneFrames() {
        val animator = FaceAnimator(Random(3))
        var t = 0.0
        for (mood in Mood.entries) {
            animator.receive(FaceState(gazeX = 3.0, gazeY = -3.0, mood = mood, talk = 0.7), t)
            val frame = run(animator, t, 2.0)
            t += 2.0
            val values = listOf(frame.gaze.x, frame.gaze.y, frame.talk, frame.closed, frame.pupil, frame.browLift,
                frame.browTilt, frame.lean, frame.hop, frame.blush, frame.squint)
            assertTrue("$mood: $values", values.all { it.isFinite() })
            assertTrue(frame.closed in 0.0..1.0 && frame.squint in 0.0..1.0)
            val clamped = FaceAnimator.clampGaze(frame.gaze)
            assertTrue(clamped.x * clamped.x + clamped.y * clamped.y <= 1.0001)
        }
    }
}
