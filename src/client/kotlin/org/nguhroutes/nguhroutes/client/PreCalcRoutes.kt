package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos
import kotlin.collections.iterator
import kotlin.math.abs

//const val supportedRoutesFormatVersion = "1.0"

// Extra cost for stopping at a station
const val stationExtraCost = 2.0

data class Connection(
    val station: String,
    val line: String,
    val fromCoords: BlockPos,
    val toCoords: BlockPos,
    val cost: Double,
    /**
     * This means that this connection is not in the direction that the line was surveyed, and that there should thus
     * be a warning that the coords may be inaccurate
     */
    val reverseDirection: Boolean,
    /**
     * This means that the fromCoords and toCoords provided are actually the average coords for that station, and that
     * if possible you should instead use the toCoords from the previous connection for the fromCoords or the fromCoords
     * for the next connection for the toCoords. This is mainly intended for interchanges, where it is better to think
     * of the connection going from the end of the previous connection to the start of the next, but it is hard to know
     * those coordinates until it is already part of a route.
     */
    val averagedCoords: Boolean = false,
)
data class PreCalcRoute(val time: Double, val conns: List<Connection>)
data class Station(val conns: MutableList<Connection>)

class PreCalcRoutes {
    val routes: Map<Pair<String, String>, PreCalcRoute>
    val stations: Map<String, Station>

    /**
     * Generate pre-calculated routes from a Network.
     */
    constructor(net: Network, noNether: Boolean) {
        val routesMut = HashMap<Pair<String, String>, PreCalcRoute>()

        // First, extract lists of which connections exist at each station
        val stationsMut = HashMap<String, Station>()

        fun addStationIfNecessary(code: String) {
            if (!stationsMut.containsKey(code)) {
                stationsMut[code] = Station(mutableListOf())
            }
        }

        // The speed that one travels at on rail, expressed in **seconds per block**.
        // This is calculated by averaging the speeds for line sections in the network, in the for loop below.
        var minecartSpeedFactor = 0.0
        // The total distance for the segments used to calculate minecartSpeedFactor, needed for calculating the average
        var minecartSpeedFactorDistance = 0.0
        val deferredConnections: MutableList<() -> Unit> = mutableListOf()
        for (line in net.lines) {
            fun addConnections(from: Stop, to: Stop) {
                addStationIfNecessary(from.code)
                addStationIfNecessary(to.code)

                fun addConnection(from: Stop, to: Stop, cost: Double, reverseDirection: Boolean) {
                    stationsMut.getValue(from.code).conns.add(
                        Connection(
                            to.code,
                            line.key,
                            from.coords,
                            to.coords,
                            cost,
                            reverseDirection
                        )
                    )
                }
                fun addBothConnections(from: Stop, to: Stop, cost: Double) {
                    addConnection(from, to, cost, false)
                    addConnection(to, from, cost, true)
                }

                if (to.time != null) {
                    if (to.dist != null) {
                        minecartSpeedFactor += to.time
                        minecartSpeedFactorDistance += to.dist
                    }
                    addBothConnections(from, to, to.time)
                } else if (to.dist != null) {
                    deferredConnections.add {
                        addBothConnections(from, to, to.dist * minecartSpeedFactor)
                    }
                } else {
                    // Use Manhattan distance to approximate the line length
                    val dist = abs(from.coords.x - to.coords.x) + abs(from.coords.z - to.coords.z)
                    deferredConnections.add {
                        addBothConnections(from, to, dist * minecartSpeedFactor)
                    }
                }
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
        // Divide to get the average.
        minecartSpeedFactor /= minecartSpeedFactorDistance
        if (!minecartSpeedFactor.isFinite() || minecartSpeedFactor <= 0.0) {
            // If we get a weird value we just use this approximation
            minecartSpeedFactor = 1.0 / 100.0
        }
        // Add the deferred connections now, when minecartSpeedFactor has been calculated.
        for (deferredConnection in deferredConnections) {
            deferredConnection()
        }

        // Add Nether connections if applicable
        if (!noNether) {
            for (connection in net.connections) {
                addStationIfNecessary(connection.overworldCode)
                addStationIfNecessary(connection.netherCode)
                fun addConnection(from: String, fromCoords: BlockPos, to: String, toCoords: BlockPos) {
                    stationsMut.getValue(from).conns.add(
                        Connection(
                            to,
                            "Interdimensional transfer",
                            fromCoords,
                            toCoords,
                            // This is the time that you have to stand in a portal
                            4.0,
                            false
                        )
                    )
                }
                addConnection(
                    connection.overworldCode,
                    connection.overworldCoords,
                    connection.netherCode,
                    connection.netherCoords
                )
                addConnection(
                    connection.netherCode,
                    connection.netherCoords,
                    connection.overworldCode,
                    connection.overworldCoords
                )
            }
        }

        // For interchanges, add connections between the stations
        for (set in net.interchanges) {
            for (stationCode in set) {
                val station = stationsMut.getValue(stationCode)
                for (s2 in set) {
                    if (stationCode == s2) continue

                    val coordsA = net.findAverageStationCoords(stationCode)
                    val coordsB = net.findAverageStationCoords(s2)
                    if (coordsA != null && coordsB != null) {
                        station.conns.add(
                            Connection(
                                s2,
                                "On foot",
                                coordsA,
                                coordsB,
                                walkTime(coordsA.toBottomCenterPos(), coordsB.toBottomCenterPos()),
                                false,
                                true
                            )
                        )
                    }
                }
            }
        }

        // Algorithm is Floyd–Warshall with path reconstruction
        val dist = HashMap<Pair<String, String>, Double>()
        // Penultimate stop for route and final connection
        val prev = HashMap<Pair<String, String>, Pair<String, Connection>?>()
        // First connection of route
        val first = HashMap<Pair<String, String>, Connection?>()

        for (i in stationsMut) {
            for (j in stationsMut) {
                dist[Pair(i.key, j.key)] = Double.POSITIVE_INFINITY
                prev[Pair(i.key, j.key)] = null
                first[Pair(i.key, j.key)] = null
            }
        }
        for (station in stationsMut) {
            for (conn in station.value.conns) {
                dist[Pair(station.key, conn.station)] = conn.cost + stationExtraCost
                prev[Pair(station.key, conn.station)] = Pair(station.key, conn)
                first[Pair(station.key, conn.station)] = conn
            }
        }
        for (k in stationsMut) {
            for (i in stationsMut) {
                for (j in stationsMut) {
                    val ij = Pair(i.key, j.key)
                    val ik = Pair(i.key, k.key)
                    val kj = Pair(k.key, j.key)
                    val dij = dist[ij] ?: Double.POSITIVE_INFINITY
                    val dik = dist[ik] ?: Double.POSITIVE_INFINITY
                    val dkj = dist[kj] ?: Double.POSITIVE_INFINITY

                    // Extra cost for transferring from one line to another at a station.
                    // Currently, staying on the same line incurs no extra cost since the distance should be zero.
                    val arriveConn = prev[ik]?.second
                    val departConn = first[kj]
                    val extraCost = calcExtraCost(arriveConn, departConn)

                    if (dij > dik + dkj + extraCost) {
                        dist[ij] = dik + dkj + extraCost
                        prev[ij] = prev[kj]
                        first[ij] = first[ik]
                    }
                }
            }
        }
        // Path reconstruction
        for (start in stationsMut) {
            stationLoop@ for (end in stationsMut) {
                if (prev[Pair(start.key, end.key)] != null) {
                    var currEndCode = end.key
                    val path = mutableListOf<Connection>()
                    while (start.key != currEndCode) {
                        val newPrev = prev[Pair(start.key, currEndCode)]
                            ?: continue@stationLoop
                        currEndCode = newPrev.first
                        path.addFirst(newPrev.second)
                    }
                    var totalCost = 0.0
                    for (i in 0..<path.size) {
                        totalCost += path[i].cost + calcExtraCost(
                            if (i != 0) path[i - 1] else null,
                            path[i]
                        )
                    }
                    routesMut[Pair(start.key, end.key)] = PreCalcRoute(totalCost, path)
                }
            }
        }

        routes = routesMut
        stations = stationsMut
    }

    // Currently this is no longer used but it's probably good to keep around in case we want to do caching.
    /**
     * Load pre-calculated routes from JSON.
     */
//    constructor(obj: JsonObject) {
//        val format = obj.getValue("format").jsonPrimitive.content
//        if (format.split('.')[0] != supportedRoutesFormatVersion.split('.')[0]) {
//            throw RuntimeException("Routes format ($format) is not supported ($supportedRoutesFormatVersion)")
//        }
//
//        val routesList = obj.getValue("routes").jsonObject
//        val routesMut = HashMap<Pair<String, String>, PreCalcRoute>()
//        for (i in routesList) {
//            val tupleArr = i.value.jsonArray
//            val time = tupleArr[0].jsonPrimitive.double
//            val arr = tupleArr[1].jsonArray
//            var route = mutableListOf<Connection>()
//            var currLine: String? = null
//            for (j in arr) {
//                when (j) {
//                    is JsonArray -> {
//                        val code = j[0].jsonPrimitive.content
//                        val line = j[1].jsonPrimitive.content
//                        route.add(Connection(code, line))
//                        currLine = line
//                    }
//                    is JsonPrimitive -> {
//                        if (currLine == null)
//                            throw RuntimeException("Line was not specified for route stop")
//                        route.add(Connection(j.content, currLine))
//                    }
//                    else -> throw RuntimeException("Route stop must be array or string")
//                }
//            }
//            val routeStrSegments = i.key.split('`')
//            routesMut[Pair(routeStrSegments[0], routeStrSegments[1])] = PreCalcRoute(time, route)
//        }
//        routes = routesMut
//    }
}

fun calcExtraCost(arriveConn: Connection?, departConn: Connection?): Double {
    val transferExtraCost = if (arriveConn != null && departConn != null) {
        // The toCoords for the arriving connection are usually just the ones provided, but if
        // averagedCoords is true, then the toCoords should be the same as the fromCoords for the
        // departing connection. Also, if both have averagedCoords as true, I'm not quite sure what
        // should happen, so I'll just use the provided ones since that situation should only happen if
        // an interchange is specified in a weird way in the network file as far as I can tell.
        val toCoords = if (arriveConn.averagedCoords && !departConn.averagedCoords) {
            departConn.fromCoords
        } else {
            arriveConn.toCoords
        }
        // Vice versa for the fromCoords
        val fromCoords = if (departConn.averagedCoords && !arriveConn.averagedCoords) {
            departConn.fromCoords
        } else {
            departConn.fromCoords
        }
        val wt = walkTime(toCoords.toBottomCenterPos(), fromCoords.toBottomCenterPos())
        if (wt < 1.0) { // Assume that a time of less than 1 seconds means no transfer
            wt
        } else {
            wt + 1.0 // Extra cost for transferring to another line
        }
    } else {
        // As far as I understand it, this case can only occur if dik + dkj is infinite,
        // and in that case it doesn't matter because nothing will be updated anyway.
        0.0
    }

    // Sum up extra costs
    return stationExtraCost + transferExtraCost
}