package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

data class RouteStop(
    val code: String,
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

class Route(startCode: String, conns: List<Connection>, network: Network, warpStart: Boolean = false) {
    val stops: List<RouteStop>

    init {
        val stopsMut = mutableListOf<RouteStop>()
        val startCoords: BlockPos = if (conns.isEmpty()) {
            // This is the case for just running there
            network.findAverageStationCoordsThrowing(startCode)
        } else {
            conns[0].fromCoords
        }
        stopsMut.add(RouteStop(
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
            stopsMut.add(RouteStop(
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
        stops = stopsMut
    }
}