package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Routes(val date: String)

class JsonData(jsonElement: JsonElement) {
    val routes: Routes

    init {
        val jsonObject = jsonElement.jsonObject
        routes = Routes(jsonObject["date"]!!.jsonPrimitive.toString())
    }
}