plugins {
    id("dev.kikugie.stonecutter")
}

// The version the shared sources in src/ are currently written for. Switch it with the "Set active project" tasks.
stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    val loader = current.project.substringAfterLast('-')

    // Versioned dependencies from stonecutter.properties.toml
    properties {
        tags(current.project.substringBeforeLast('-'), loader)
    }

    // `//? if fabric {` and `//? if neoforge {` in the sources
    constants {
        match(loader, "fabric", "neoforge")
    }

    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
        // 26.1 renamed GUI rendering to extracting render state
        string(current.parsed >= "26.1") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("graphics.drawCenteredString(", "graphics.centeredText(")
            replace("graphics.drawString(", "graphics.text(")
            replace("public void render(", "public void extractRenderState(")
            replace(".render(graphics, mouseX, mouseY, partialTick)", ".extractRenderState(graphics, mouseX, mouseY, partialTick)")
            replace("public void renderContent(", "public void extractContent(")
        }
        string(current.parsed >= "26.1") {
            replace("renderer.state.CameraRenderState", "renderer.state.level.CameraRenderState")
        }
        // 26.2 moved screens, toasts and the hidden HUD from the client to its GUI
        string(current.parsed >= "26.2") {
            replace("minecraft.setScreen(", "minecraft.gui.setScreen(")
            replace("minecraft.screen", "minecraft.gui.screen()")
            replace("Minecraft.getInstance().screen", "Minecraft.getInstance().gui.screen()")
            replace("minecraft.getToastManager()", "minecraft.gui.toastManager()")
            replace("minecraft.options.hideGui", "minecraft.gui.hud.isHidden()")
        }
        string(current.parsed >= "26.3") {
            replace("InputConstants.Type.KEYSYM", "InputConstants.Type.KEYBOARD")
        }
        // Fabric API took Mojang's names with 26.1
        string(current.parsed >= "26.1") {
            replace("keybinding.v1.KeyBindingHelper", "keymapping.v1.KeyMappingHelper")
            replace("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
            replace("ScreenEvents.afterRender(", "ScreenEvents.afterExtract(")
            replace("PayloadTypeRegistry.playC2S()", "PayloadTypeRegistry.serverboundPlay()")
            replace("PayloadTypeRegistry.playS2C()", "PayloadTypeRegistry.clientboundPlay()")
        }
    }
}
