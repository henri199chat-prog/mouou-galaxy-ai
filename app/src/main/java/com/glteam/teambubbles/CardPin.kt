package com.glteam.teambubbles

/**
 * When the open card may close (spec §3 "Tap → card", §22 checklist "outside tap ignored while typing;
 * collapses when empty"). A draft is never thrown away by an accidental tap.
 */
object CardPin {
    /** Tap outside the card: closes it unless there is a draft to keep (keyboard up or not). */
    fun outsideTapCollapses(draft: CharSequence): Boolean = draft.isBlank()

    /** The 25 s idle timer: only when nobody is typing and nothing is drafted. */
    fun idleMayCollapse(typing: Boolean, draft: CharSequence): Boolean = !typing && draft.isBlank()
}
