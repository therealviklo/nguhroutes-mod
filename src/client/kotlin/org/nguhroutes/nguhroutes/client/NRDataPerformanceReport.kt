package org.nguhroutes.nguhroutes.client

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.text.Text
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

class NRDataPerformanceReport {
    class Timer(val ts: TimeSource.Monotonic) {
        private var startTime: TimeSource.Monotonic.ValueTimeMark? = null
        private var stopTime: TimeSource.Monotonic.ValueTimeMark? = null

        fun start() {
            startTime = ts.markNow()
        }

        fun stop() {
            stopTime = ts.markNow()
        }

        fun seconds(): Double {
            val startTime = startTime
            val stopTime = stopTime
            if (startTime == null || stopTime == null) {
                return Double.NaN
            }
            return (stopTime - startTime).toDouble(DurationUnit.SECONDS)
        }
    }

    val ts = TimeSource.Monotonic

    val downloadTime = Timer(ts)
    val networkTime = Timer(ts)
    val preCalcRoutesTime = Timer(ts)
    val minecartSpeedTime = Timer(ts)
    val netherConnectionTime = Timer(ts)
    val interchangesTime = Timer(ts)
    val pathFindingAlgoTime = Timer(ts)
    val pathFindingAlgoSetupTime = Timer(ts)
    val pathFindingAlgoMainLoopTime = Timer(ts)
    val pathFindingAlgoPathReconstructionTime = Timer(ts)

    fun getReport(): List<String> {
        val list = mutableListOf<String>()
        list.add("Download: %.1f s".format(downloadTime.seconds()))
        list.add("Network file parsing: %.1f s".format(networkTime.seconds()))
        list.add("Pre-calculating routes: %.1f s".format(preCalcRoutesTime.seconds()))
        list.add("|Minecart speed calculation: %.1f s".format(minecartSpeedTime.seconds()))
        list.add("|Nether connections processing: %.1f s".format(netherConnectionTime.seconds()))
        list.add("|Interchanges processing: %.1f s".format(interchangesTime.seconds()))
        list.add("|Pathfinding algorithm: %.1f s".format(pathFindingAlgoTime.seconds()))
        list.add("||Setup: %.1f s".format(pathFindingAlgoSetupTime.seconds()))
        list.add("||Main loop: %.1f s".format(pathFindingAlgoMainLoopTime.seconds()))
        list.add("||Path reconstruction: %.1f s".format(pathFindingAlgoPathReconstructionTime.seconds()))
        return list
    }

    fun sendReportMessage(feedback: ClientPlayerEntity) {
        val report = getReport()
        for (line in report) {
            feedback.sendMessage(Text.of(line), false)
        }
    }
}