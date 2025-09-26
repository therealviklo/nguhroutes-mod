package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Connection(val station: String, val line: String)
data class Routes(val date: String, val routes: Map<String, List<Connection>>)

class JsonData(routesJson: JsonElement) {
    val routes: Routes

    init {
        val routesObj = routesJson.jsonObject
        val date = routesObj.getValue("date").jsonPrimitive.content
        val routesList = routesObj.getValue("routes").jsonObject
        val routesMap = HashMap<String, List<Connection>>()
        for (i in routesList) {
            val arr = i.value.jsonArray
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
            routesMap[i.key] = route
        }
        routes = Routes(date, routesMap)
    }
}