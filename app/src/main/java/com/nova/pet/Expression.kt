package com.nova.pet

/**
 * Geometry targets for one facial expression. The renderer eases the current face
 * toward these values every frame, so switching expression always animates smoothly.
 *
 * All sizes are multipliers of the base eye/mouth size; offsets are fractions of the
 * eye height. Tilt is in degrees (positive = outer corner raised).
 */
data class FaceShape(
    val leftOpen: Float = 1f,
    val rightOpen: Float = 1f,
    val eyeWidth: Float = 1f,
    val leftTilt: Float = 0f,
    val rightTilt: Float = 0f,
    val leftLift: Float = 0f,
    val rightLift: Float = 0f,
    val mouthWidth: Float = 1f,
    val mouthThickness: Float = 1f,
    /** >0 smile, <0 frown, in mouth-widths. */
    val mouthCurve: Float = 0f,
    /** Raises the right end of the mouth (smirk), in mouth-widths. */
    val mouthSkew: Float = 0f,
    val glow: Float = 1f,
    /** 0 = NØVA cyan, 1 = amber alert colour (errors only). */
    val alert: Float = 0f,
)

/** Continuous motion layered on top of the shape while an expression is showing. */
enum class Motion { NONE, SCAN, PULSE, DANCE, GLITCH }

/**
 * Every expression NØVA can show.
 *
 * @param holdMs how long a one-off reaction stays before returning to the base state
 *               (0 = only used as a base/persistent state).
 * @param priority a reaction can only replace a still-running reaction of equal or lower priority.
 */
enum class Expression(
    val shape: FaceShape,
    val holdMs: Long,
    val priority: Int = 1,
    val motion: Motion = Motion.NONE,
) {
    IDLE(FaceShape(), 0, priority = 0),
    HAPPY(FaceShape(leftOpen = 0.55f, rightOpen = 0.55f, leftLift = -0.08f, rightLift = -0.08f,
        mouthWidth = 1.5f, mouthCurve = 0.22f, glow = 1.25f), 1600),
    MISCHIEVOUS(FaceShape(leftOpen = 0.5f, rightOpen = 1.05f, leftTilt = -10f, rightTilt = 6f,
        mouthWidth = 1.2f, mouthCurve = 0.08f, mouthSkew = 0.18f), 1800),
    CURIOUS(FaceShape(leftOpen = 1.15f, rightOpen = 0.8f, rightTilt = -8f, rightLift = 0.06f,
        mouthWidth = 0.6f, mouthThickness = 1.2f), 1800),
    SURPRISED(FaceShape(leftOpen = 1.25f, rightOpen = 1.25f, eyeWidth = 1.12f,
        mouthWidth = 0.45f, mouthThickness = 2.4f, glow = 1.35f), 1200, priority = 2),
    CONFUSED(FaceShape(leftOpen = 0.65f, rightOpen = 1.1f, leftTilt = 8f, rightTilt = -4f,
        leftLift = 0.08f, mouthWidth = 0.9f, mouthCurve = -0.05f, mouthSkew = -0.12f), 1800),
    ANNOYED(FaceShape(leftOpen = 0.4f, rightOpen = 0.4f, leftTilt = -12f, rightTilt = -12f,
        mouthWidth = 1.1f, mouthCurve = -0.06f), 1400, priority = 2, motion = Motion.GLITCH),
    THINKING(FaceShape(leftOpen = 0.8f, rightOpen = 0.8f, leftLift = -0.05f, rightLift = -0.05f,
        mouthWidth = 0.5f, mouthSkew = 0.06f), 2500, motion = Motion.SCAN),
    LISTENING(FaceShape(leftOpen = 1.1f, rightOpen = 1.1f, eyeWidth = 1.05f,
        mouthWidth = 0.5f, mouthThickness = 1.4f, glow = 1.3f), 3000, motion = Motion.PULSE),
    DANCING(FaceShape(leftOpen = 0.6f, rightOpen = 0.6f, leftLift = -0.06f, rightLift = -0.06f,
        mouthWidth = 1.3f, mouthCurve = 0.18f, glow = 1.2f), 4000, motion = Motion.DANCE),
    ERROR(FaceShape(leftOpen = 0.7f, rightOpen = 0.55f, leftTilt = 6f, rightTilt = -6f,
        mouthWidth = 0.9f, mouthCurve = -0.08f, alert = 1f), 1800, priority = 3, motion = Motion.GLITCH),
    SLEEPY(FaceShape(leftOpen = 0.28f, rightOpen = 0.28f, leftLift = 0.12f, rightLift = 0.12f,
        mouthWidth = 0.7f, glow = 0.6f), 0, priority = 0),
    SLEEPING(FaceShape(leftOpen = 0.06f, rightOpen = 0.06f, leftLift = 0.15f, rightLift = 0.15f,
        mouthWidth = 0.55f, mouthThickness = 0.8f, glow = 0.35f), 0, priority = 0);

    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /** Reactions picked at random when the face is tapped. */
        val TAP_REACTIONS = listOf(HAPPY, MISCHIEVOUS, CURIOUS, SURPRISED, HAPPY)

        /** Small silent things NØVA does on its own while idle. */
        val IDLE_BEHAVIOURS = listOf(CURIOUS, MISCHIEVOUS, HAPPY, THINKING)
    }
}
