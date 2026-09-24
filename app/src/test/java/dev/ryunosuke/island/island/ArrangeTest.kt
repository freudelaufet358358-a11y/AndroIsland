package dev.ryunosuke.island.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangeTest {
    private fun media(key: String = "media:x", title: String = "曲") = MediaActivity(
        key, "x", title, "a", null, 0, true, 0, 0, 0, 1f,
        canPrevious = true, canNext = true, canSeek = true, skipByTime = false, open = null,
    )

    private fun timer(key: String = "t", running: Boolean = true) = TimerActivity(
        key, "clock", null, Chrono(running, 0, 0, true), null, emptyList(), 0, null,
    )

    private fun incoming(key: String = "call") = CallActivity(
        key, "dialer", true, "山田", null, null, null, false, emptyList(), 0, null,
    )

    @Test fun priorityDecidesPrimaryAndSecondary() {
        val arr = Arrange.arrange(listOf(media(), timer()), Interaction())
        assertEquals("t", arr.primary?.key)
        assertEquals("media:x", arr.secondary?.key)
        assertFalse(arr.expanded)
    }

    @Test fun incomingCallAutoExpandsUntilCollapsed() {
        val call = incoming()
        assertTrue(Arrange.arrange(listOf(media(), call), Interaction()).expanded)
        val collapsed = Interaction(collapsedAuto = setOf(Arrange.autoKey(call)))
        val arr = Arrange.arrange(listOf(media(), call), collapsed)
        assertEquals("call", arr.primary?.key)
        assertFalse(arr.expanded)
    }

    @Test fun swipedActivitiesAreCountedAndCanBeRestored() {
        // しまったものの数を島に伝える（待機時の島を残して、そこをタップで戻せるように）
        val m = media()
        val t = timer()
        val hidden = Interaction(hidden = mapOf(m.key to m.revision, t.key to t.revision))
        val arr = Arrange.arrange(listOf(m, t), hidden)
        assertNull(arr.primary)
        assertEquals(2, arr.hidden)
        // 戻す = しまった記録を消す
        val restored = Arrange.arrange(listOf(m, t), hidden.copy(hidden = emptyMap()))
        assertEquals("t", restored.primary?.key)
        assertEquals(0, restored.hidden)
        // 終わった活動はしまった数に入らない
        assertEquals(1, Arrange.arrange(listOf(t), hidden).hidden)
    }

    @Test fun swipedActivityStaysHiddenUntilRevisionChanges() {
        val m = media(title = "A")
        val hidden = Interaction(hidden = mapOf(m.key to m.revision))
        assertNull(Arrange.arrange(listOf(m), hidden).primary)
        // 次の曲になったら戻ってくる
        assertEquals(m.key, Arrange.arrange(listOf(media(title = "B")), hidden).primary?.key)
    }

    @Test fun focusBringsSecondaryForward() {
        val i = Interaction(focusKey = "media:x", expandedKey = "media:x")
        val arr = Arrange.arrange(listOf(media(), timer()), i)
        assertEquals("media:x", arr.primary?.key)
        assertTrue(arr.expanded)
    }

    @Test fun incomingCallBeatsFocus() {
        val i = Interaction(focusKey = "media:x", expandedKey = "media:x")
        val arr = Arrange.arrange(listOf(media(), incoming()), i)
        assertEquals("call", arr.primary?.key)
    }

    @Test fun samePriorityKeepsInputOrder() {
        val arr = Arrange.arrange(listOf(timer("new"), timer("old")), Interaction())
        assertEquals("new", arr.primary?.key)
        assertEquals("old", arr.secondary?.key)
    }

    @Test fun pruneDropsVanishedKeys() {
        val i = Interaction(hidden = mapOf("gone" to 1), focusKey = "gone", expandedKey = "gone", collapsedAuto = setOf("gone|1"))
        assertEquals(Interaction(), Arrange.prune(i, listOf(media())))
    }
}
