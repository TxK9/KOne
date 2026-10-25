package io.nekohasekai.sfa.compose.base

/** Only fixed, known messages may cross from core/import failures into the interface. */
internal fun publicErrorMessage(message: String?): String = when (message) {
    "Settings could not be applied. Try again.",
    "Could not import the paired profile. Try again in Add profile.",
    "QR scanning was interrupted. Try again or scan an image.",
    "The claim code is invalid or expired.",
    "This account has reached its device limit.",
    "Access revoked",
    "Service paused",
    "No route has been assigned.",
    "The received configuration cannot be used.",
    "The new configuration failed to start. The previous one was restored.",
    "Configuration updated",
    "Service unavailable",
    -> message
    else -> "Action incomplete. Check your input or try again later."
}
