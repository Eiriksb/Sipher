package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.models.Catalog;
import io.github.eiriksb.sipher.models.LanguagePacks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Pick the language you speak and read, and download or remove language packs. */
public final class LanguagesScreen extends Screen {
    private static final String SOURCE = "github.com/Eiriksb/Sipher";

    private final Screen parent;
    private PackList list;
    private boolean anyDownloading;

    public LanguagesScreen(Screen parent) {
        super(Component.translatable("sipher.languages.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LanguagePacks packs = CaptionEngine.packs();
        if (packs == null) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                    .bounds(width / 2 - 100, height - 28, 200, 20).build());
            return;
        }

        List<String> usable = new ArrayList<>(List.of("en"));
        packs.catalog().languages().stream().filter(packs::installed).map(Catalog.Language::code).forEach(usable::add);
        Map<String, String> names = CaptionEngine.languageNames();
        addRenderableWidget(languagePicker(width / 2 - 205, 30, "sipher.languages.speak", SipherClientConfig.SPOKEN_LANGUAGE, usable, names));
        addRenderableWidget(languagePicker(width / 2 + 5, 30, "sipher.languages.read", SipherClientConfig.READING_LANGUAGE, usable, names));

        list = new PackList(58, height - 58 - 44);
        for (Catalog.Language language : packs.catalog().languages()) {
            list.add(new PackEntry(language));
        }
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private CycleButton<String> languagePicker(int x, int y, String key, ModConfigSpec.ConfigValue<String> setting,
                                               List<String> codes, Map<String, String> names) {
        String current = codes.contains(setting.get()) ? setting.get() : "en";
        return CycleButton.<String>builder(code -> Component.literal(names.getOrDefault(code, code)))
                .withValues(codes)
                .withInitialValue(current)
                .create(x, y, 200, 20, Component.translatable(key), (button, code) -> {
                    setting.set(code);
                    SipherClientConfig.SPEC.save();
                    CaptionEngine.reconfigure();
                });
    }

    @Override
    public void tick() {
        LanguagePacks packs = CaptionEngine.packs();
        if (packs == null || list == null) {
            return;
        }
        boolean downloading = packs.catalog().languages().stream()
                .anyMatch(language -> packs.status(language).state() == LanguagePacks.State.DOWNLOADING);
        if (anyDownloading && !downloading) {
            rebuildWidgets(); // a download finished: refresh the pickers and buttons
        }
        anyDownloading = downloading;
        list.children().forEach(PackEntry::refresh);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (CaptionEngine.packs() == null) {
            graphics.drawCenteredString(font, Component.translatable("sipher.status.starting"), width / 2, height / 2, 0xA0A0A0);
            return;
        }
        graphics.drawCenteredString(font, Component.translatable("sipher.languages.footer", SOURCE).withStyle(ChatFormatting.GRAY),
                width / 2, height - 42, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static String megabytes(long bytes) {
        return String.valueOf(Math.max(1, Math.round(bytes / 1_000_000.0)));
    }

    private void confirmDownload(Catalog.Language language) {
        LanguagePacks packs = CaptionEngine.packs();
        String licences = language.components().stream().distinct()
                .map(id -> packs.catalog().component(id).license()).distinct().collect(Collectors.joining(", "));
        minecraft.setScreen(new ConfirmScreen(accepted -> {
            if (accepted) {
                packs.install(language, installed -> CaptionEngine.reconfigure());
            }
            minecraft.setScreen(this);
        }, Component.translatable("sipher.languages.confirm.title", language.name()),
                Component.translatable("sipher.languages.confirm.body", megabytes(packs.remainingBytes(language)), SOURCE, licences),
                Component.translatable("sipher.languages.download"), Component.translatable("gui.cancel")));
    }

    private final class PackList extends ContainerObjectSelectionList<PackEntry> {
        PackList(int top, int listHeight) {
            super(LanguagesScreen.this.minecraft, LanguagesScreen.this.width, listHeight, top, 24);
        }

        void add(PackEntry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return 400;
        }
    }

    private final class PackEntry extends ContainerObjectSelectionList.Entry<PackEntry> {
        private final Catalog.Language language;
        private final Button action;
        private Component status = Component.empty();

        PackEntry(Catalog.Language language) {
            this.language = language;
            this.action = Button.builder(Component.empty(), button -> press()).size(90, 20).build();
            refresh();
        }

        void refresh() {
            LanguagePacks packs = CaptionEngine.packs();
            LanguagePacks.Status state = packs.status(language);
            boolean downloadsAllowed = SipherClientConfig.ALLOW_DOWNLOADS.get();
            action.active = true;
            action.setTooltip(null);
            switch (state.state()) {
                case BUILT_IN -> {
                    status = Component.translatable("sipher.languages.built_in");
                    action.visible = false;
                }
                case INSTALLED -> {
                    status = Component.translatable("sipher.languages.installed").withStyle(ChatFormatting.GREEN);
                    action.setMessage(Component.translatable("sipher.languages.delete"));
                    boolean inUse = language.code().equals(SipherClientConfig.SPOKEN_LANGUAGE.get())
                            || language.code().equals(SipherClientConfig.READING_LANGUAGE.get());
                    action.active = !inUse;
                    if (inUse) {
                        action.setTooltip(Tooltip.create(Component.translatable("sipher.languages.in_use")));
                    }
                }
                case DOWNLOADING -> {
                    status = Component.translatable("sipher.languages.downloading", Math.round(state.progress() * 100));
                    action.setMessage(Component.translatable("gui.cancel"));
                }
                case AVAILABLE, FAILED -> {
                    status = state.state() == LanguagePacks.State.FAILED
                            ? Component.translatable("sipher.languages.failed").withStyle(ChatFormatting.RED)
                            : Component.translatable("sipher.languages.size", megabytes(packs.remainingBytes(language)));
                    action.setMessage(Component.translatable("sipher.languages.download"));
                    action.active = downloadsAllowed;
                    if (!downloadsAllowed) {
                        action.setTooltip(Tooltip.create(Component.translatable("sipher.languages.downloads_off")));
                    } else if (state.error() != null) {
                        action.setTooltip(Tooltip.create(Component.literal(state.error())));
                    }
                }
            }
        }

        private void press() {
            LanguagePacks packs = CaptionEngine.packs();
            switch (packs.status(language).state()) {
                case AVAILABLE, FAILED -> confirmDownload(language);
                case DOWNLOADING -> packs.cancel(language);
                case INSTALLED -> {
                    try {
                        packs.delete(language);
                    } catch (IOException e) {
                        Sipher.LOGGER.warn("Could not delete language pack {}", language.code(), e);
                    }
                    rebuildWidgets();
                }
                default -> {
                }
            }
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            graphics.drawString(font, Component.literal(language.name()), left + 4, top + 6, 0xFFFFFF, false);
            graphics.drawString(font, Component.literal(language.englishName()).withStyle(ChatFormatting.GRAY),
                    left + 120, top + 6, 0xA0A0A0, false);
            graphics.drawString(font, status, left + 210, top + 6, 0xD0D0D0, false);
            action.setPosition(left + width - 94, top);
            action.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(action);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(action);
        }
    }
}
