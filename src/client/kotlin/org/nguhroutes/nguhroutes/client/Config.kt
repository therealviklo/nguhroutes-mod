package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

@Serializable
data class Config(
    var debug: Boolean = false,
    var your_doom: Boolean = false,
) {
    fun saveConfig() {
        try {
            val mcFolder = FabricLoader.getInstance().configDir
            val configFolder = mcFolder / "nguhroutes"

            if (!configFolder.exists()) {
                configFolder.createDirectories()
            }

            val configFile = configFolder / "config.json"

            val jsonText = Json { prettyPrint = true }.encodeToString(this)
            configFile.toFile().writeText(jsonText)

        } catch (_: Exception) {}
    }
}

fun loadConfig(): Config {
    return try {
        val mcFolder = FabricLoader.getInstance().configDir
        val file = (mcFolder / "nguhroutes" / "config.json").toFile()
        if (file.exists()) {
            val jsonText = file.readText()
            Json { ignoreUnknownKeys = true }.decodeFromString<Config>(jsonText)
        } else {
            Config()
        }
    } catch (_: Exception) {
        Config()
    }
}
