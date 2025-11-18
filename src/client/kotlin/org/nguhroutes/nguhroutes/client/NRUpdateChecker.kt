package org.nguhroutes.nguhroutes.client

import com.terraformersmc.modmenu.api.UpdateChannel
import com.terraformersmc.modmenu.api.UpdateChecker
import com.terraformersmc.modmenu.api.UpdateInfo
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import kotlin.math.min

/**
 * Update checker for ModMenu
 */
class NRUpdateChecker : UpdateChecker {
    private class NRUpdateInfo(val updateNeeded: Boolean, val link: String) : UpdateInfo {
        override fun isUpdateAvailable(): Boolean {
            return updateNeeded
        }

        override fun getDownloadLink(): String {
            return link
        }

        override fun getUpdateChannel(): UpdateChannel {
            return UpdateChannel.RELEASE
        }
    }

    override fun checkForUpdates(): UpdateInfo? {
        try {
            val container = FabricLoader.getInstance().getModContainer("nguhroutes").orElse(null)
            val version = container?.metadata?.version?.friendlyString ?: return null

            val json = downloadJson("https://api.github.com/repos/therealviklo/nguhroutes-mod/releases/latest").jsonObject
            val latestVersion = json.getValue("tag_name").jsonPrimitive.content

            return NRUpdateInfo(
                latestIsNewer(version, latestVersion),
                json["html_url"]?.jsonPrimitive?.content
                    ?: "https://github.com/therealviklo/nguhroutes-mod/releases/latest"
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun latestIsNewer(current: String, latest: String): Boolean {
        fun splitUpdateString(update: String): List<Int> {
            return update.split('.').map { it.takeWhile { it.isDigit() }.toInt() }
        }
        val currentSplit = splitUpdateString(current)
        val latestSplit = splitUpdateString(latest)
        if (latestSplit.size > currentSplit.size)
            return true
        for (i in 0..<min(currentSplit.size, latestSplit.size)) {
            if (currentSplit[i] > latestSplit[i])
                return false
            if (currentSplit[i] < latestSplit[i])
                return true
        }
        return false
    }
}