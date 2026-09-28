package dev.ryunosuke.island.source

import dev.ryunosuke.island.island.PodsBattery
import dev.ryunosuke.island.island.PodsBattery.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodsMetadataTest {
    private fun meta(vararg kv: Pair<Int, String?>): Map<Int, ByteArray?> = kv.associate { (k, v) -> k to v?.toByteArray() }

    @Test fun readsWhatBtHelperWrites() {
        // BtHelper（Evolution X）は数字を 10 進の文字列、充電中を大文字の TRUE / FALSE で書く
        val b = PodsMetadata.parse(
            meta(
                PodsMetadata.LEFT_BATTERY to "85", PodsMetadata.RIGHT_BATTERY to "90", PodsMetadata.CASE_BATTERY to "40",
                PodsMetadata.LEFT_CHARGING to "FALSE", PodsMetadata.RIGHT_CHARGING to "FALSE", PodsMetadata.CASE_CHARGING to "TRUE",
                PodsMetadata.MAIN_BATTERY to "85", PodsMetadata.MAIN_CHARGING to "FALSE",
            ),
        )
        assertEquals(PodsBattery(Part(85, false), Part(90, false), Part(40, true), Part(85, false)), b)
        assertTrue(b!!.hasParts)
        // 島のコンパクトには左右の低い方
        assertEquals(85, b.headset)
    }

    @Test fun unknownPartsAreNull() {
        // 不明は BATTERY_LEVEL_UNKNOWN（-1）。書かれていないキーは null で届く
        val b = PodsMetadata.parse(
            meta(PodsMetadata.LEFT_BATTERY to "70", PodsMetadata.RIGHT_BATTERY to "-1", PodsMetadata.CASE_BATTERY to null),
        )!!
        assertEquals(Part(70, false), b.left)
        assertNull(b.right)
        assertNull(b.case)
        assertEquals(70, b.headset)
    }

    @Test fun headphonesHaveOnlyTheMainBattery() {
        // AirPods Max などは左右とケースを書かない
        val b = PodsMetadata.parse(meta(PodsMetadata.MAIN_BATTERY to "55", PodsMetadata.MAIN_CHARGING to "true"))!!
        assertFalse(b.hasParts)
        assertEquals(Part(55, true), b.main)
        assertEquals(55, b.headset)
    }

    @Test fun nothingKnownIsNull() {
        assertNull(PodsMetadata.parse(emptyMap()))
        assertNull(PodsMetadata.parse(meta(PodsMetadata.LEFT_BATTERY to "-1", PodsMetadata.MAIN_BATTERY to "abc")))
        // 範囲の外は読まない
        assertNull(PodsMetadata.parse(meta(PodsMetadata.MAIN_BATTERY to "101")))
    }

    @Test fun freshOnlyWhenTheBroadcastMatchesTheMainBattery() {
        val b = PodsBattery(Part(85, false), Part(90, false), null, Part(85, false))
        // BtHelper の知らせ（左右の低い方）
        assertTrue(PodsMetadata.matches(b, 85))
        // HFP でイヤホン自身が送ってくる 10% 刻みの値や、前の接続の古い値
        assertFalse(PodsMetadata.matches(b, 80))
        assertFalse(PodsMetadata.matches(PodsBattery(left = Part(85, false)), 85))
    }
}
