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

const val supportedNetworkFormatVersion = "4.0"

data class Stop(val code: String, val coords: BlockPos, val time: Double? = null, val dist: Double? = null)
data class Line(
    val name: String,
    val stops: List<Stop?>, // Kind of a dirty solution but null means impassable/reset
    val loop: Boolean
)
data class NetherConnection(val overworldCode: String, val overworldCoords: BlockPos, val netherCode: String, val netherCoords: BlockPos)

class Network(obj: JsonObject) {
    val format: String = obj.getValue("format").jsonPrimitive.content

    init {
        if (format.split('.')[0] != supportedNetworkFormatVersion.split('.')[0]) {
            throw RuntimeException("Network format ($format) is not supported (supported: $supportedNetworkFormatVersion). Perhaps you need to update the mod?")
        }
    }

//    val version: String = obj.getValue("version").jsonPrimitive.content
//    val date: String = obj.getValue("date").jsonPrimitive.content
    val lines: Map<String, Line>
    val connections: List<NetherConnection>
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
                    val coordsVal = stopObj["coords"] ?: continue
                    val code = prefixes.getValue(dimension.key) + stopObj.getValue("code").jsonPrimitive.content
                    val coords = coordsVal.jsonArray
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

        val connectionsMut = mutableListOf<NetherConnection>()
        val connectionsObject = obj["connections"]
        if (connectionsObject != null) {
            for (connection in connectionsObject.jsonArray) {
                val connArr = connection.jsonArray
                fun getConnCodeCoords(end: JsonObject): Pair<String, BlockPos> {
                    val code = end.getValue("code").jsonPrimitive.content
                    val coordsArr = end.getValue("coords").jsonArray
                    val coords = BlockPos(
                        coordsArr[0].jsonPrimitive.int,
                        coordsArr[1].jsonPrimitive.int,
                        coordsArr[2].jsonPrimitive.int
                    )
                    return Pair(code, coords)
                }
                val overworldEnd = connArr[0].jsonObject
                val netherEnd = connArr[1].jsonObject
                val overworldCodeCoords = getConnCodeCoords(overworldEnd)
                val netherCodeCoords = getConnCodeCoords(netherEnd)
                connectionsMut.add(NetherConnection(
                    overworldCodeCoords.first,
                    overworldCodeCoords.second,
                    prefixes["the_nether"] + netherCodeCoords.first,
                    netherCodeCoords.second)
                )
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
                // People might want to search with an ampersand
                val nameListSize = nameList.size
                for (i in 0..<nameListSize) {
                    if (nameList[i].contains('⁊')) {
                        nameList.add(nameList[i].replace('⁊', '&'))
                    }
                }
                // or with "and"
                val nameListSize2 = nameList.size
                for (i in 0..<nameListSize2) {
                    if (nameList[i].contains('&')) {
                        nameList.add(nameList[i].replace("&", "and"))
                    }
                }
                stationNamesMut[station.key] = nameList
            }
        }
        stationNames = stationNamesMut
    }

    fun findAverageStationCoords(code: String): BlockPos? {
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
            return null
        return BlockPos((x / n).toInt(), (y / n).toInt(), (z / n).toInt())
    }

    fun findAverageStationCoordsThrowing(code: String): BlockPos {
        return findAverageStationCoords(code)
            ?: throw RuntimeException("Cannot find coordinates for $code")
    }
}