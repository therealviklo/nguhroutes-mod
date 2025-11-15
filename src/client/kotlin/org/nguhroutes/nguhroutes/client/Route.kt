package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos

data class RouteStop(
    val code: String?,
    val coords: BlockPos,
    val dimension: String,
    val lineCode: String?,
    val lineName: String,
    val fromCoordsDim: Pair<BlockPos, String>?,
    /**
     * This means that this connection is not in the direction that the line was surveyed, and that there should thus
     * be a warning that the coords may be inaccurate
     */
    val reverseDirection: Boolean,
    val debugTime: Double? = null,
    val debugExtraTime: Double? = null,
)

class Route {
    val stops: MutableList<RouteStop> = mutableListOf()

    constructor(startCode: String, conns: List<Connection>, network: Network, warpStart: Boolean = false) {
        val startCoords: BlockPos = if (conns.isEmpty()) {
            // This is the case for just running there
            network.findAverageStationCoordsThrowing(startCode)
        } else {
            conns[0].fromCoords
        }
        stops.add(RouteStop(
            startCode,
            startCoords,
            getDim(startCode),
            null,
            if (warpStart) "Warp" else "Start",
            null,
            false
        ))

        var lastDimension = getDim(startCode)
        for (i in conns.indices) {
            val conn = conns[i]
            // Get the appropriate coords, depending on if the connection has averagedCoords or not. If averagedCoords
            // is true, then the appropriate coords are the fromCoords for the next connection, if there is one. Also,
            // if there are two connections with averagedCoords in a row, just use the provided coords, but that should
            // rarely happen.
            val toCoords: BlockPos = if (conn.averagedCoords && i < conns.size - 1 && !conns[i + 1].averagedCoords) {
                conns[i + 1].fromCoords
            } else {
                conn.toCoords
            }
            // Really, it should check for averagedCoords here too, but currently that only ever happens if the
            // connection is on foot, in which case it doesn't matter.
            val fromCoordsDim = if (conn.line == "On foot") {
                null
            } else {
                Pair(conn.fromCoords, lastDimension)
            }
            stops.add(RouteStop(
                conn.station,
                toCoords,
                getDim(conn.station),
                conn.line,
                network.lines[conn.line]?.name ?: conn.line,
                fromCoordsDim,
                conn.reverseDirection,
                conn.cost,
                calcExtraCost(
                    if (i != 0) conns[i - 1] else null,
                    conn
                )
            ))
            lastDimension = getDim(conn.station)
        }
    }

    // This is for just sprinting to a coordinate
    constructor(coords: BlockPos, dimension: String) {
        stops.add(RouteStop(
            null,
            coords,
            dimension,
            null,
            "On foot",
            null,
            false
        ))
    }
}