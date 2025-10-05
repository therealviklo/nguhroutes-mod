package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import kotlin.collections.iterator
import kotlin.math.abs

const val supportedRoutesFormatVersion = "1.0"

/**
 * The speed that one travels at on rail, expressed in **seconds per block**.
 */
const val minecartSpeedFactor: Double = 1.0 / 100.0

data class Connection(val station: String, val line: String)
data class PreCalcRoute(val time: Double, val conns: List<Connection>)

class PreCalcRoutes {
    val routes: Map<Pair<String, String>, PreCalcRoute>

    /**
     * Generate pre-calculated routes from a Network.
     */
    constructor(net: Network, noNether: Boolean) {
        val routesMut = HashMap<Pair<String, String>, PreCalcRoute>()

        // First, extract lists of which connections exist at each station
        data class CostConnection(val conn: Connection, val cost: Double)
        data class Station(val conns: MutableList<CostConnection>)
        val stations = HashMap<String, Station>()

        fun addStationIfNecessary(code: String) {
            if (!stations.containsKey(code)) {
                stations[code] = Station(mutableListOf())
            }
        }

        for (line in net.lines) {
            fun addConnections(from: Stop, to: Stop) {
                addStationIfNecessary(from.code)
                addStationIfNecessary(to.code)

                val cost: Double = if (to.time != null) {
                    to.time
                } else if (to.dist != null) {
                    to.dist * minecartSpeedFactor
                } else {
                    // Use Manhattan distance to approximate the line length
                    val dist = abs(from.coords.x - to.coords.x) + abs(from.coords.z - to.coords.z)
                    dist * minecartSpeedFactor
                }
                fun addConnection(from: Stop, to: Stop) {
                    stations.getValue(from.code).conns.add(CostConnection(Connection(to.code, line.key), cost))
                }
                addConnection(from, to)
                addConnection(to, from)
            }
            var firstStop: Stop? = null
            var prevStop: Stop? = null
            for (stop in line.value.stops) {
                if (firstStop == null) {
                    firstStop = stop
                }
                if (prevStop != null && stop != null) { // stop is null when the line segment is impassable
                    addConnections(prevStop, stop)
                }
                prevStop = stop
            }
            if (line.value.loop && prevStop != null && firstStop != null) {
                addConnections(prevStop, firstStop)
            }
        }

        // Add Nether connections if applicable
        if (!noNether) {
            for (connection in net.connections) {
                addStationIfNecessary(connection.first)
                addStationIfNecessary(connection.second)
                fun addConnection(from: String, to: String) {
                    stations.getValue(from).conns.add(CostConnection(Connection(
                        to,
                        "Interdimensional transfer"),
                        // This is the time that you have to stand in a portal, but it would be better to approximate
                        // the time it takes to get to the portal
                        4.0
                    ))
                }
                addConnection(connection.first, connection.second)
                addConnection(connection.second, connection.first)
            }
        }

        // Algorithm is Floyd–Warshall with path reconstruction
        val dist = HashMap<Pair<String, String>, Double>()
        val prev = HashMap<Pair<String, String>, Pair<String, CostConnection>?>()

        for (i in stations) {
            for (j in stations) {
                dist[Pair(i.key, j.key)] = Double.POSITIVE_INFINITY
                prev[Pair(i.key, j.key)] = null
            }
        }
        for (station in stations) {
            for (conn in station.value.conns) {
                dist[Pair(station.key, conn.conn.station)] = conn.cost
                prev[Pair(station.key, conn.conn.station)] = Pair(station.key, conn)
            }
        }
        for (k in stations) {
            for (i in stations) {
                for (j in stations) {
                    val ij = Pair(i.key, j.key)
                    val ik = Pair(i.key, k.key)
                    val kj = Pair(k.key, j.key)
                    val dij = dist[ij] ?: Double.POSITIVE_INFINITY
                    val dik = dist[ik] ?: Double.POSITIVE_INFINITY
                    val dkj = dist[kj] ?: Double.POSITIVE_INFINITY
                    if (dij > dik + dkj) {
                        dist[ij] = dik + dkj
                        prev[ij] = prev[kj]
                    }
                }
            }
        }
        // Path reconstruction
        for (start in stations) {
            stationLoop@ for (end in stations) {
                if (prev[Pair(start.key, end.key)] != null) {
                    var currEndCode = end.key
                    val path = mutableListOf<CostConnection>()
                    while (start.key != currEndCode) {
                        val newPrev = prev[Pair(start.key, currEndCode)]
                            ?: continue@stationLoop
                        currEndCode = newPrev.first
                        path.addFirst(newPrev.second)
                    }
                    val totalCost = path.fold(0.0, { acc, i -> acc + i.cost })
                    routesMut[Pair(start.key, end.key)] = PreCalcRoute(totalCost, path.map { conn -> conn.conn })
                }
            }
        }

        routes = routesMut
    }

    // Currently this is no longer used but it's probably good to keep around in case we want to do caching.
    /**
     * Load pre-calculated routes from JSON.
     */
    constructor(obj: JsonObject) {
        val format = obj.getValue("format").jsonPrimitive.content
        if (format.split('.')[0] != supportedRoutesFormatVersion.split('.')[0]) {
            throw RuntimeException("Routes format ($format) is not supported ($supportedRoutesFormatVersion)")
        }

        val routesList = obj.getValue("routes").jsonObject
        val routesMut = HashMap<Pair<String, String>, PreCalcRoute>()
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
            val routeStrSegments = i.key.split('`')
            routesMut[Pair(routeStrSegments[0], routeStrSegments[1])] = PreCalcRoute(time, route)
        }
        routes = routesMut
    }
}