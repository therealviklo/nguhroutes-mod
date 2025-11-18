package org.nguhroutes.nguhroutes.client

import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text


class ConfigScreen(val config: Config, val parent: Screen?) : Screen(Text.of("NguhRoutes Config")) {
    override fun init() {
        val buttonWidget: ButtonWidget? = ButtonWidget.builder(Text.of("Hello World")) { _ ->
            this.close()
        }.dimensions(40, 40, 120, 20).build()
        this.addDrawableChild(buttonWidget)
    }

    override fun close() {
        this.client?.setScreen(parent)
    }
}