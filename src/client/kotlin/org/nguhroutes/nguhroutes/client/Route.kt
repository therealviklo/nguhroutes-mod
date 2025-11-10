package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

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
)

class Route {
    val stops: MutableList<RouteStop> = mutableListOf()

    constructor(startCode: String, conns: List<Connection>, network: Network, warpStart: Boolean = false) {
        // Do some preprocessing to get rid of oddities that could maybe occur in the data from PreCalcRoutes
        val conns = conns.filterIndexed { index, connection -> index == conns.size - 1 || connection.line != "On foot" }

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
            val coords: BlockPos = conns[i].toCoords
            stops.add(RouteStop(
                conn.station,
                coords,
                getDim(conn.station),
                conn.line,
                network.lines[conn.line]?.name ?: conn.line,
                Pair(conn.fromCoords, lastDimension),
                conn.reverseDirection
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