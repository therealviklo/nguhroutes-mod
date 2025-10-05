package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.util.math.BlockPos
import kotlin.collections.iterator

const val supportedNetworkFormatVersion = "2.0"

data class Stop(val code: String, val coords: BlockPos, val time: Double? = null, val dist: Double? = null)
data class Line(
    val name: String,
    val stops: List<Stop?>, // Kind of a dirty solution but null means impassable/reset
    val loop: Boolean
)

class Network(obj: JsonObject) {
    val format: String = obj.getValue("format").jsonPrimitive.content

    init {
        if (format.split('.')[0] != supportedNetworkFormatVersion.split('.')[0]) {
            throw RuntimeException("Network format ($format) is not supported ($supportedNetworkFormatVersion)")
        }
    }

//    val version: String = obj.getValue("version").jsonPrimitive.content
//    val date: String = obj.getValue("date").jsonPrimitive.content
    val lines: Map<String, Line>
    val connections: List<Pair<String, String>>
    val stationNames: Map<String, List<String>>

    init {
        val linesObj = obj.getValue("lines").jsonObject
        val linesMut = mutableMapOf<String, Line>()
        for (dimension in linesObj) {
            for (line in dimension.value.jsonArray) {
                val lineObj = line.jsonObject
                val lineCode = lineObj.getValue("code").jsonPrimitive.content
                val lineName = lineObj["name"]?.jsonPrimitive?.content ?: lineCode
                val stops = lineObj.getValue("stops").jsonArray
                val stopsMut = mutableListOf<Stop?>()
                for (stop in stops) {
                    when (stop) {
                        is JsonPrimitive -> {
                            if (stop.content == "impassable") {
                                stopsMut.add(null)
                                continue
                            }
                        }
                        else -> {}
                    }
                    val stopObj = stop.jsonObject
                    val code = prefixes.getValue(dimension.key) + stopObj.getValue("code").jsonPrimitive.content
                    val coords = stopObj.getValue("coords").jsonArray
                    val x = coords[0].jsonPrimitive.int
                    val y = coords[1].jsonPrimitive.int
                    val z = coords[2].jsonPrimitive.int
                    stopsMut.add(Stop(
                        code,
                        BlockPos(x, y, z),
                        stopObj["time"]?.jsonPrimitive?.doubleOrNull,
                        stopObj["dist"]?.jsonPrimitive?.doubleOrNull
                    ))
                }
                linesMut[lineCode] = Line(lineName, stopsMut, lineObj["loop"]?.jsonPrimitive?.boolean ?: false)
            }
        }
        lines = linesMut

        val connectionsMut = mutableListOf<Pair<String, String>>()
        val connectionsObject = obj["connections"]
        if (connectionsObject != null) {
            for (connection in connectionsObject.jsonArray) {
                when (connection) {
                    is JsonArray -> {
                        val overworldCode = connection[0].jsonPrimitive.content
                        val netherCode = prefixes["the_nether"] + connection[1].jsonPrimitive.content
                        connectionsMut.add(Pair(overworldCode, netherCode))
                    }
                    is JsonPrimitive -> {
                        val code = connection.content
                        connectionsMut.add(Pair(code, prefixes["the_nether"] + code))
                    }
                    else -> throw RuntimeException("There is a connection is nether a string nor an array")
                }
            }
        }
        connections = connectionsMut

        val stationNamesMut = mutableMapOf<String, List<String>>()
        val stationsObj = obj.get("stations")
        if (stationsObj != null) {
            for (station in stationsObj.jsonObject) {
                val nameList = mutableListOf<String>()
                when (val names = station.value) {
                    is JsonArray -> {
                        for (name in names) {
                            nameList.add(name.jsonPrimitive.content)
                        }
                    }
                    is JsonPrimitive -> {
                        val name = names.content
                        if (name.startsWith('$')) {
                            val referee = name.substring(1)
                            when (val names = stationsObj.jsonObject[referee]) {
                                is JsonArray -> {
                                    for (name in names) {
                                        nameList.add(name.jsonPrimitive.content)
                                    }
                                }
                                is JsonPrimitive -> {
                                    nameList.add(names.content)
                                }
                                else -> throw RuntimeException("Station ${station.key} points to ${referee}, which has a name that is neither a string nor an array")
                            }
                        } else {
                            nameList.add(name)
                        }
                    }
                    else -> throw RuntimeException("Station ${station.key} has a name that is neither a string nor an array")
                }
                stationNamesMut[station.key] = nameList
            }
        }
        stationNames = stationNamesMut
    }

    fun getStop(code: String, line: String): Stop {
        // TODO: what if a station is on a line multiple times
        return lines.getValue(line).stops.find{ it != null && it.code == code }
            ?: throw IllegalArgumentException("Unable to find stop $code on $line")
    }

    fun findAverageStationCoords(code: String): BlockPos {
        var x: Long = 0
        var y: Long = 0
        var z: Long = 0
        var n: Long = 0
        for (line in lines) {
            for (stop in line.value.stops) {
                if (stop != null) {
                    if (stop.code == code) {
                        x += stop.coords.x
                        y += stop.coords.y
                        z += stop.coords.z
                        n += 1
                    }
                }
            }
        }
        if (n == 0L)
            throw IllegalArgumentException("Station $code does not exist")
        return BlockPos((x / n).toInt(), (y / n).toInt(), (z / n).toInt())
    }
}