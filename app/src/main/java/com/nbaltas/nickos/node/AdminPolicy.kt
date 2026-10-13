package com.nbaltas.nickos.node

/**
 * When the kid tablet should ask a grown-up to turn on Device Admin. Without it the screen
 * cannot be switched off (bedtime, the Master's screen button), and Device Owner already covers it.
 */
object AdminPolicy {
    /** Shown on the system "Activate device admin app" screen. Plain words, no em or en dashes. */
    const val EXPLANATION = "NickOS needs this to turn the screen off at bedtime and when a parent asks. It cannot do anything else with it."

    /** Screen-off works when the app is Device Owner or an active admin. */
    fun canLockScreen(isOwner: Boolean, isAdminActive: Boolean) = isOwner || isAdminActive

    fun needsAdmin(isOwner: Boolean, isAdminActive: Boolean) = !canLockScreen(isOwner, isAdminActive)

    /** Only a Node, only with a grown-up in the menu (never in front of the kid), only once per run. */
    fun shouldPromptHere(isNode: Boolean, parentMenuOpen: Boolean, isOwner: Boolean, isAdminActive: Boolean, alreadyAsked: Boolean) =
        isNode && parentMenuOpen && needsAdmin(isOwner, isAdminActive) && !alreadyAsked
}
