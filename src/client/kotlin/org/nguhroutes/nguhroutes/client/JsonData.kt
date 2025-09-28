package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.util.math.BlockPos
import kotlin.collections.getValue
import kotlin.collections.iterator

data class Connection(val station: String, val line: String)
data class PreCalcRoute(val time: Double, val conns: List<Connection>)

class Routes(obj: JsonObject) {
    val date: String = obj.getValue("date").jsonPrimitive.content
    val routes: Map<String, PreCalcRoute>

    init {
        val routesList = obj.getValue("routes").jsonObject
        val routesMap = HashMap<String, PreCalcRoute>()
        for (i in routesList) {
            val tupleArr = i.value.jsonArray
            val time = tupleArr[0].jsonPrimitive.double
            val arr = tupleArr[1].jsonArray
            var route = mutableListOf<Connection>()
            var currLine: String? = null
            for (j in arr) {
                when (j) {
                    is JsonArray -> {
                        val code = j[0].jsonPrimitive.content
                        val line = j[1].jsonPrimitive.content
                        route.add(Connection(code, line))
                        currLine = line
                    }
                    is JsonPrimitive -> {
                        if (currLine == null)
                            throw RuntimeException("Line was not specified for route stop")
                        route.add(Connection(j.content, currLine))
                    }
                    else -> throw RuntimeException("Route stop must be array or string")
                }
            }
            routesMap[i.key] = PreCalcRoute(time, route)
        }
        routes = routesMap
    }
}

data class Stop(val code: String, val coords: BlockPos)
data class Line(val name: String, val stops: List<Stop>)

class Network(obj: JsonObject) {
//    val version: String = obj.getValue("version").jsonPrimitive.content
//    val date: String = obj.getValue("date").jsonPrimitive.content
    val lines: Map<String, Line>

    init {
        val linesObj = obj.getValue("lines").jsonObject
        val linesMut = mutableMapOf<String, Line>()
        for (dimension in linesObj) {
            for (line in dimension.value.jsonArray) {
                val lineObj = line.jsonObject
                val lineCode = lineObj.getValue("code").jsonPrimitive.content
                val lineName = lineObj.get("name")?.jsonPrimitive?.content ?: lineCode
                val stops = lineObj.getValue("stops").jsonArray
                val stopsMut = mutableListOf<Stop>()
                for (stop in stops) {
                    val stopObj = stop.jsonObject
                    val code = prefixes.getValue(dimension.key) + stopObj.getValue("code").jsonPrimitive.content
                    val coords = stopObj.getValue("coords").jsonArray
                    val x = coords[0].jsonPrimitive.int
                    val y = coords[1].jsonPrimitive.int
                    val z = coords[2].jsonPrimitive.int
                    stopsMut.add(Stop(code, BlockPos(x, y, z)))
                }
                linesMut[lineCode] = Line(lineName, stopsMut)
            }
        }
        lines = linesMut
    }

    fun getStop(code: String, line: String): Stop {
        // TODO: what if a station is on a line multiple times
        return lines.getValue(line).stops.find{ it.code == code }
            ?: throw IllegalArgumentException("Unable to find stop $code on $line")
    }

    fun findAverageStationCoords(code: String): BlockPos {
        var x: Long = 0
        var y: Long = 0
        var z: Long = 0
        var n: Long = 0
        for (line in lines) {
            for (stop in line.value.stops) {
                if (stop.code == code) {
                    x += stop.coords.x
                    y += stop.coords.y
                    z += stop.coords.z
                    n += 1
                }
            }
        }
        if (n == 0L)
            throw IllegalArgumentException("Station does not exist")
        return BlockPos((x / n).toInt(), (y / n).toInt(), (z / n).toInt())
    }
}

class JsonData(routesJson: JsonElement, networkJson: JsonElement) {
    val routes = Routes(routesJson.jsonObject)
    val network = Network(networkJson.jsonObject)
}