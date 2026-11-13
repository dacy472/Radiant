/*
 * Concept adapted from Mihon's `ViewerNavigation` (https://github.com/mihonapp/mihon),
 * Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer

import android.graphics.PointF

enum class NavigationRegion { MENU, PREV, NEXT, LEFT, RIGHT }

/** Maps a tap position (fractions of the viewer, 0..1) to an action. Pure, unit-testable. */
abstract class ViewerNavigation {
    abstract fun getAction(pos: PointF): NavigationRegion
    fun getAction(x: Float, y: Float): NavigationRegion = getAction(PointF(x, y))
}

/** Taps always toggle the menu (tap zones disabled). */
object MenuOnlyNavigation : ViewerNavigation() {
    override fun getAction(pos: PointF) = NavigationRegion.MENU
}

/**
 * Paged viewers: left third → [NavigationRegion.LEFT], right third → [NavigationRegion.RIGHT],
 * centre → menu. Same split the Compose pager used.
 */
object PagedNavigation : ViewerNavigation() {
    override fun getAction(pos: PointF): NavigationRegion = when {
        pos.x < 1f / 3f -> NavigationRegion.LEFT
        pos.x > 2f / 3f -> NavigationRegion.RIGHT
        else -> NavigationRegion.MENU
    }
}

/** Vertical strips: top quarter scrolls up, bottom quarter scrolls down, the rest toggles the menu. */
object WebtoonNavigation : ViewerNavigation() {
    override fun getAction(pos: PointF): NavigationRegion = when {
        pos.y < 0.25f -> NavigationRegion.PREV
        pos.y > 0.75f -> NavigationRegion.NEXT
        else -> NavigationRegion.MENU
    }
}

/** Vertical pager: same as [WebtoonNavigation] (top/bottom taps move one page). */
object VerticalPagerNavigation : ViewerNavigation() {
    override fun getAction(pos: PointF): NavigationRegion = WebtoonNavigation.getAction(pos)
}
