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
)

enum class Expression(val shape: FaceShape, val holdMs: Long) {
    IDLE(FaceShape(), 0),
    HAPPY(FaceShape(leftOpen = 0.55f, rightOpen = 0.55f, leftLift = -0.08f, rightLift = -0.08f,
        mouthWidth = 1.5f, mouthCurve = 0.22f, glow = 1.25f), 1600),
    MISCHIEVOUS(FaceShape(leftOpen = 0.5f, rightOpen = 1.05f, leftTilt = -10f, rightTilt = 6f,
        mouthWidth = 1.2f, mouthCurve = 0.08f, mouthSkew = 0.18f), 1800),
    CURIOUS(FaceShape(leftOpen = 1.15f, rightOpen = 0.8f, rightTilt = -8f, rightLift = 0.06f,
        mouthWidth = 0.6f, mouthThickness = 1.2f), 1800),
    SURPRISED(FaceShape(leftOpen = 1.25f, rightOpen = 1.25f, eyeWidth = 1.12f,
        mouthWidth = 0.45f, mouthThickness = 2.4f, glow = 1.35f), 1200),
    CONFUSED(FaceShape(leftOpen = 0.65f, rightOpen = 1.1f, leftTilt = 8f, rightTilt = -4f,
        leftLift = 0.08f, mouthWidth = 0.9f, mouthCurve = -0.05f, mouthSkew = -0.12f), 1800),
    ANNOYED(FaceShape(leftOpen = 0.4f, rightOpen = 0.4f, leftTilt = -12f, rightTilt = -12f,
        mouthWidth = 1.1f, mouthCurve = -0.06f), 1400),
    SLEEPY(FaceShape(leftOpen = 0.28f, rightOpen = 0.28f, leftLift = 0.12f, rightLift = 0.12f,
        mouthWidth = 0.7f, glow = 0.6f), 0),
    SLEEPING(FaceShape(leftOpen = 0.06f, rightOpen = 0.06f, leftLift = 0.15f, rightLift = 0.15f,
        mouthWidth = 0.55f, mouthThickness = 0.8f, glow = 0.35f), 0);

    companion object {
        /** Reactions picked at random when the face is tapped. */
        val TAP_REACTIONS = listOf(HAPPY, MISCHIEVOUS, CURIOUS, SURPRISED, HAPPY)
    }
}
