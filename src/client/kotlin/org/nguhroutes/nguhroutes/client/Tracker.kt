package org.nguhroutes.nguhroutes.client

import net.minecraft.client.util.Clipboard
import net.minecraft.util.math.BlockPos
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class Tracker(initialPos: BlockPos) {
    private val startPos: BlockPos = initialPos
    private var prevPos: BlockPos = initialPos
    private var endPos: BlockPos? = null
    private var dist: Int = 0
    private val startTime = System.currentTimeMillis()
    private var endTime: Long? = null
    val active: Boolean
        get() = endTime == null || endPos == null

    fun updatePos(newPos: BlockPos) {
        if (newPos != prevPos) {
            dist += abs(newPos.x - prevPos.x) + abs(newPos.z - prevPos.z)
            prevPos = newPos
        }
    }

    fun stop(endPos: BlockPos) {
        endTime = System.currentTimeMillis()
        updatePos(endPos)
        this.endPos = endPos
    }

    fun copyEndStop() {
        val endTime = endTime
        val endPos = endPos
        if (endTime != null && endPos != null) {
            // Divides by 1000 but in two steps so that it rounds to one decimal
            val time = (endTime - startTime) / 100 / 10.0
            val date = ZonedDateTime.now(ZoneOffset.UTC).withNano(0).format(DateTimeFormatter.ISO_INSTANT)
            val clipboard = Clipboard()
            clipboard.setClipboard(0,
                """
                    |,
                    |					{
                    |						"code": "",
                    |						"coords": [${endPos.x}, ${endPos.y}, ${endPos.z}],
                    |						"dist": $dist,
                    |						"time": $time,
                    |						"date": "$date"
                    |					}
                """.trimMargin())
        }
    }

    fun copyBothStops() {
        val endTime = endTime
        val endPos = endPos
        if (endTime != null && endPos != null) {
            // Divides by 1000 but in two steps so that it rounds to one decimal
            val time = (endTime - startTime) / 100 / 10.0
            val date = ZonedDateTime.now(ZoneOffset.UTC).withNano(0).format(DateTimeFormatter.ISO_INSTANT)
            val clipboard = Clipboard()
            clipboard.setClipboard(0,
                """
                    |					{
                    |						"code": "",
                    |						"coords": [${startPos.x}, ${startPos.y}, ${startPos.z}],
                    |						"date": "$date"
                    |					},
                    |					{
                    |						"code": "",
                    |						"coords": [${endPos.x}, ${endPos.y}, ${endPos.z}],
                    |						"dist": $dist,
                    |						"time": $time,
                    |						"date": "$date"
                    |					}
                """.trimMargin())
        }
    }
}