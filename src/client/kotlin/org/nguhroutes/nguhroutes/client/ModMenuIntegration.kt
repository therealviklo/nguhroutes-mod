package org.nguhroutes.nguhroutes.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import com.terraformersmc.modmenu.api.UpdateChecker

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent ->
            ConfigScreen(config, parent)
        }
    }

    override fun getProvidedUpdateCheckers(): Map<String?, UpdateChecker?> {
        return mapOf("nguhroutes" to NRUpdateChecker())
    }
}