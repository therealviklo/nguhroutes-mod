package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos

data class RouteStop(val code: String, val position: BlockPos, val line: String)

class Route(startCode: String, conns: List<Connection>, network: Network) {
    val stops: List<RouteStop>

    init {
        if (conns.isEmpty())
            throw IllegalArgumentException("Empty routes are not allowed")

        val stopsMut = mutableListOf<RouteStop>()

        val startLine = conns[0].line
        val startCoords: BlockPos = if (startLine == "Interdimensional transfer") {
            // If it starts with an interdimensional transfer we need to determine the station location by
            // averaging all coords
            network.findAverageStationCoords(startCode)
        } else {
            // Otherwise we check the station's coords on the initial line
            network.getStop(startCode, startLine).coords
        }
        stopsMut.add(RouteStop(startCode, startCoords, "Start"))

        for (i in conns.indices) {
            val conn = conns[i]
            val coords: BlockPos = if (conn.line == "Interdimensional transfer") {
                // If it is an interdimensional transfer we need to determine coords based on where you go next
                if (i + 1 < conns.size) {
                    // Determine based on which line you take next
                    val nextConn = conns[i + 1]
                    network.getStop(conn.station, nextConn.line).coords
                } else {
                    // This is the final stop, so we just average all coords for this station
                    network.findAverageStationCoords(conn.station)
                }
            } else {
                // Otherwise we just look up the coords for the station on the line you take to get there
                network.getStop(conn.station, conn.line).coords
            }
            stopsMut.add(RouteStop(
                conn.station,
                coords,
                conn.line))
        }
        stops = stopsMut
    }
}