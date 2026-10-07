package org.filezilla.android.ui

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * Runs every coroutine the controllers launched into [this] scope to the end,
 * then returns.
 *
 * The controllers are fire-and-forget: a method launches into the view model's
 * scope and returns at once, the work landing later. A unit test hands in its
 * own scope instead and waits here, by joining the scope's children rather
 * than by sleeping -- so a test reads the state only once the work behind it is
 * actually done, with no wall-clock race and nothing left running to disturb
 * the next test. A plain `.value` read then sees the result: a background write
 * to a [androidx.compose.runtime.MutableState] lands in the global snapshot at
 * once, with no apply notification to wait for.
 */
internal fun CoroutineScope.drain() {
    // Read the scope's own children here, outside runBlocking -- inside its
    // lambda `coroutineContext` would be runBlocking's own, whose children are
    // none, so the join would wait for nothing and return at once.
    val launched = coroutineContext[Job]!!.children.toList()
    runBlocking { launched.joinAll() }
}

/**
 * Drains any task left on the main looper, for a test to call once it is done.
 *
 * Writing a [androidx.compose.runtime.MutableState] off the main thread makes
 * Compose's global snapshot manager post an apply-notification to the main
 * looper. Left there, it lingers into the next test class and can disturb one
 * whose own timing leans on that looper (the refresh indicator). Clearing it
 * in teardown keeps each test's footprint to itself.
 */
internal fun quiesceMainLooper() {
    shadowOf(Looper.getMainLooper()).idle()
}
