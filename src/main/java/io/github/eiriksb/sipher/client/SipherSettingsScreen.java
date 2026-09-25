package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.config.SipherClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class SipherSettingsScreen extends Screen {
    private static final int COLUMN_WIDTH = 200;
    private static final int GAP = 10;
    private static final int ROW = 24;

    private final Screen parent;

    public SipherSettingsScreen(Screen parent) {
        super(Component.translatable("sipher.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - COLUMN_WIDTH - GAP / 2;
        int right = width / 2 + GAP / 2;
        int top = Math.max(32, height / 2 - 90);

        toggle(left, top, "sipher.settings.captions", SipherClientConfig.CAPTIONS_ENABLED);
        toggle(left, top + ROW, "sipher.settings.share", SipherClientConfig.SHARE_MY_CAPTIONS);
        toggle(left, top + ROW * 2, "sipher.settings.live", SipherClientConfig.LIVE_PARTIALS);
        toggle(left, top + ROW * 3, "sipher.settings.transcript", SipherClientConfig.TRANSCRIPT_ENABLED);
        toggle(left, top + ROW * 4, "sipher.settings.own_bubbles", SipherClientConfig.SHOW_OWN_BUBBLES);
        toggle(left, top + ROW * 5, "sipher.settings.other_bubbles", SipherClientConfig.SHOW_OTHER_BUBBLES);

        addRenderableWidget(new ConfigSlider(right, top, 0.5, 2.5, SipherClientConfig.BUBBLE_SCALE.get(),
                value -> SipherClientConfig.BUBBLE_SCALE.set(Math.round(value * 20) / 20.0),
                value -> Component.translatable("sipher.settings.bubble_scale", Math.round(value * 100))));
        addRenderableWidget(new ConfigSlider(right, top + ROW, 100, 400, SipherClientConfig.BUBBLE_WRAP_WIDTH.get(),
                value -> SipherClientConfig.BUBBLE_WRAP_WIDTH.set((int) Math.round(value)),
                value -> Component.translatable("sipher.settings.bubble_width", Math.round(value))));
        addRenderableWidget(new ConfigSlider(right, top + ROW * 2, 0, 1, SipherClientConfig.BUBBLE_BACKGROUND_OPACITY.get(),
                value -> SipherClientConfig.BUBBLE_BACKGROUND_OPACITY.set(Math.round(value * 20) / 20.0),
                value -> Component.translatable("sipher.settings.bubble_background", Math.round(value * 100))));
        addRenderableWidget(new ConfigSlider(right, top + ROW * 3, 1000, 30000, SipherClientConfig.BUBBLE_DISPLAY_MS.get(),
                value -> SipherClientConfig.BUBBLE_DISPLAY_MS.set((int) (Math.round(value / 500) * 500)),
                value -> Component.translatable("sipher.settings.bubble_time", Math.round(value / 100) / 10.0)));
        addRenderableWidget(Button.builder(Component.translatable("sipher.settings.languages"), button -> {
                })
                .bounds(right, top + ROW * 4, COLUMN_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("sipher.settings.languages.soon")))
                .build()).active = false;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, top + ROW * 7 + 6, 200, 20)
                .build());
    }

    private void toggle(int x, int y, String key, ModConfigSpec.BooleanValue value) {
        addRenderableWidget(CycleButton.onOffBuilder(value.get())
                .create(x, y, COLUMN_WIDTH, 20, Component.translatable(key), (button, enabled) -> value.set(enabled)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int top = Math.max(32, height / 2 - 90);
        graphics.drawCenteredString(font, title, width / 2, top - 20, 0xFFFFFF);
        graphics.drawCenteredString(font, status(), width / 2, top + ROW * 6 + 6, 0xA0A0A0);
    }

    private static Component status() {
        Component speech = switch (CaptionEngine.state()) {
            case STARTING -> Component.translatable("sipher.status.starting");
            case READY -> Component.translatable("sipher.status.ready");
            case FAILED -> Component.translatable("sipher.status.failed", CaptionEngine.message());
        };
        Component relay = Component.translatable(CaptionEngine.serverRelays() ? "sipher.status.relay_on" : "sipher.status.relay_off");
        return Component.empty().append(speech).append(" · ").append(relay);
    }

    @Override
    public void onClose() {
        SipherClientConfig.SPEC.save();
        minecraft.setScreen(parent);
    }

    private static final class ConfigSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final DoubleConsumer setter;
        private final DoubleFunction<Component> label;

        ConfigSlider(int x, int y, double min, double max, double current, DoubleConsumer setter, DoubleFunction<Component> label) {
            super(x, y, COLUMN_WIDTH, 20, Component.empty(), (current - min) / (max - min));
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.label = label;
            updateMessage();
        }

        private double current() {
            return min + (max - min) * value;
        }

        @Override
        protected void updateMessage() {
            setMessage(label.apply(current()));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
        }
    }
}
