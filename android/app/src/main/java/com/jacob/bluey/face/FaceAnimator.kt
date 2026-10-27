package com.jacob.bluey.face

import com.jacob.bluey.link.FaceState
import com.jacob.bluey.link.Mood
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Double, private val stiffness: Double = 200.0, private val damping: Double = 0.7) {
    var velocity = 0.0

    fun step(target: Double, dt: Double) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

data class Vec(val x: Double, val y: Double)

/**
 * Smooths what the laptop asks for into lifelike motion: darting eyes, brows, blinks, leaning, hops.
 * A line-by-line port of FaceAnimator in iOS/Sources/FaceView.swift. Times are in seconds.
 */
class FaceAnimator(private val random: Random = Random.Default) {
    data class Frame(
        val gaze: Vec,
        val mood: Mood,
        val talk: Double,
        val closed: Double,     // 0 open … 1 shut (blink)
        val breathe: Double,
        val time: Double,
        val pupil: Double,      // pupil size multiplier
        val browLift: Double,   // + raises the brows (in design px)
        val browTilt: Double,   // + worried/curious, - focused (radians)
        val lean: Double,       // head tilt toward where he's looking (radians)
        val hop: Double,        // little idle hop (design px, up is +)
        val blush: Double,      // 0…1
        val squint: Double,     // 0…1 happy squint from below
    )

    private var target = FaceState()
    private var lastPacket = -100.0
    private val gazeX = Spring(0.0, stiffness = 260.0, damping = 0.82)
    private val gazeY = Spring(0.0, stiffness = 260.0, damping = 0.82)
    private val pupil = Spring(1.0, stiffness = 160.0, damping = 0.5)
    private val browLift = Spring(0.0, stiffness = 180.0, damping = 0.55)
    private val browTilt = Spring(0.0, stiffness = 150.0, damping = 0.6)
    private val lean = Spring(0.0, stiffness = 60.0, damping = 0.8)
    private val hop = Spring(0.0, stiffness = 260.0, damping = 0.35)
    private val blush = Spring(0.25, stiffness = 40.0, damping = 1.0)
    private val squint = Spring(0.0, stiffness = 150.0, damping = 0.7)
    private var talk = 0.0
    private var mood = Mood.LISTENING
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = 2.0
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderTarget = Vec(0.0, -0.3)
    private var jitter = Vec(0.0, 0.0)
    private var nextJitter = 0.0
    private var nextHop = 6.0

    /** Set while he's awake and talking with you. In follow mode his eyes stay locked on your mouse. */
    var awake = false

    private fun between(low: Double, high: Double) = random.nextDouble(low, high)

    fun receive(face: FaceState, time: Double) {
        target = face
        lastPacket = time
    }

    /** True while the laptop is driving the face (a packet in the last 2.5 seconds). */
    fun live(now: Double) = now - lastPacket < 2.5

    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20)
        lastTime = now

        val live = live(now)
        val wantedMood = if (live) target.mood else Mood.LISTENING
        if (wantedMood != mood) {
            mood = wantedMood
            blinkStart = now          // blink through every mood change
            if (mood != Mood.SLEEPY && mood != Mood.RESTING) {
                hop.velocity += 260   // and a little bounce of surprise
                pupil.velocity += 3
            }
        }

        // Where to look, plus tiny darting movements so the eyes never sit dead still.
        var want = if (live) {
            Vec(target.gazeX, target.gazeY)
        } else {
            if (now > nextSaccade) {
                wanderTarget = Vec(between(-0.9, 0.9), between(-0.9, 0.4))
                nextSaccade = now + between(0.6, 2.2)
            }
            wanderTarget
        }
        if (mood == Mood.THINKING) want = Vec(0.6 + 0.08 * sin(now * 1.3), -0.85)
        if (now > nextJitter) {
            // While he's listening his eyes stay on your mouse; the livelier darting is for replying.
            val amount = if (!awake) 0.015 else when (mood) {
                Mood.TALKING -> 0.07
                Mood.THINKING -> 0.05
                else -> 0.02
            }
            jitter = Vec(between(-amount, amount), between(-amount, amount))
            nextJitter = now + between(0.5, 1.4)
        }
        gazeX.step(want.x + jitter.x, dt)
        gazeY.step(want.y + jitter.y, dt)

        val wantTalk = if (live) target.talk else 0.0
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        // Expression targets per mood.
        var wantPupil = 1.0
        var wantLift = 0.0
        var wantTilt = 0.0
        var wantBlush = 0.25
        var wantSquint = 0.0
        when (mood) {
            Mood.LISTENING -> { wantPupil = 1.12; wantLift = 10.0 }
            Mood.TALKING -> { wantPupil = 1.05; wantLift = 6 + talk * 18; wantTilt = 0.05 * sin(now * 2.3) }
            Mood.POINTING -> { wantPupil = 0.95; wantLift = -4.0; wantTilt = -0.14 }
            Mood.THINKING -> { wantPupil = 0.9; wantLift = 4.0; wantTilt = 0.22 }
            Mood.HAPPY -> { wantPupil = 1.2; wantLift = 16.0; wantBlush = 0.85; wantSquint = 1.0 }
            Mood.RESTING -> { wantPupil = 0.9; wantLift = -8.0; wantBlush = 0.15 }
            Mood.SLEEPY -> { wantPupil = 0.88; wantLift = -10.0; wantTilt = 0.12; wantBlush = 0.15 }
        }
        pupil.step(wantPupil, dt)
        browLift.step(wantLift, dt)
        browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt)
        squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        // An occasional happy little hop when nothing much is going on.
        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.SLEEPY && mood != Mood.RESTING) hop.velocity += between(180.0, 320.0)
            nextHop = now + between(7.0, 14.0)
        }
        hop.step(0.0, dt)

        // Blinks, sometimes doubled. When he's drowsy they're slow and heavy.
        val drowsy = mood == Mood.SLEEPY
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && random.nextDouble() < 0.25
            nextBlink = now + if (drowsy) between(1.6, 3.0) else between(2.0, 5.0)
        }
        val p = (now - blinkStart) / (if (drowsy) 0.7 else 0.15)
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * (if (mood == Mood.RESTING) 1.1 else 1.8))
        return Frame(
            gaze = Vec(gazeX.value, gazeY.value), mood = mood, talk = talk, closed = closed,
            breathe = breathe, time = now, pupil = pupil.value, browLift = browLift.value,
            browTilt = browTilt.value, lean = lean.value, hop = hop.value, blush = blush.value,
            squint = max(0.0, min(1.0, squint.value)),
        )
    }

    companion object {
        /** Gaze limited to the unit circle, as the drawing expects. */
        fun clampGaze(g: Vec): Vec {
            val length = hypot(g.x, g.y)
            return if (length > 1) Vec(g.x / length, g.y / length) else g
        }
    }
}
