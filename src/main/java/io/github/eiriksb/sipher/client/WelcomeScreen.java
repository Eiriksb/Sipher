package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.models.Catalog;
import io.github.eiriksb.sipher.models.LanguagePacks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shown once, before anything is shared: which language the player speaks and reads, and whether their captions go to
 * players who can hear them. Closing it in any way keeps the choices on screen.
 */
public final class WelcomeScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int TEXT_WIDTH = 320;

    private final Screen parent;
    private String language;
    private boolean share;
    private boolean picked;
    private boolean packsReady;
    private int rowsTop;
    private MultiLineLabel text = MultiLineLabel.EMPTY;
    private Button download;
    private Button done;

    public WelcomeScreen(Screen parent) {
        super(Component.translatable("sipher.welcome.title"));
        this.parent = parent;
        this.language = SipherClientConfig.SPOKEN_LANGUAGE.get();
        this.share = SipherClientConfig.SHARE_MY_CAPTIONS.get();
    }

    /** The language pack matching Minecraft's own language, if there is one. */
    private static String suggestedLanguage() {
        String code = Minecraft.getInstance().options.languageCode.toLowerCase(Locale.ROOT);
        String base = code.contains("_") ? code.substring(0, code.indexOf('_')) : code;
        if (base.equals("no") || base.equals("nn")) {
            base = "nb";
        }
        LanguagePacks packs = CaptionEngine.packs();
        return packs != null && packs.catalog().language(base).isPresent() ? base : "en";
    }

    @Override
    protected void init() {
        LanguagePacks packs = CaptionEngine.packs();
        packsReady = packs != null;
        if (!picked && language.equals("en")) {
            language = suggestedLanguage();
        }
        List<String> codes = new ArrayList<>(List.of("en"));
        if (packs != null) {
            packs.catalog().languages().forEach(pack -> codes.add(pack.code()));
        }
        if (!codes.contains(language)) {
            language = "en";
        }

        text = MultiLineLabel.create(font, Component.translatable("sipher.welcome.text"), TEXT_WIDTH);
        int top = Math.max(16, height / 2 - 100);
        rowsTop = top + 20 + text.getLineCount() * 10 + 12;
        int left = width / 2 - WIDTH / 2;

        Map<String, String> names = CaptionEngine.languageNames();
        addRenderableWidget(CycleButton.<String>builder(code -> Component.literal(names.getOrDefault(code, code)))
                .withValues(codes)
                .withInitialValue(language)
                .withTooltip(code -> Tooltip.create(Component.translatable("sipher.welcome.language.tooltip")))
                .create(left, rowsTop, WIDTH, 20, Component.translatable("sipher.welcome.language"), (button, code) -> {
                    language = code;
                    picked = true;
                    updateButtons();
                }));
        addRenderableWidget(CycleButton.onOffBuilder(share)
                .withTooltip(value -> Tooltip.create(Component.translatable("sipher.welcome.share.tooltip")))
                .create(left, rowsTop + 24, WIDTH, 20, Component.translatable("sipher.settings.share"),
                        (button, value) -> share = value));

        download = addRenderableWidget(Button.builder(Component.translatable("sipher.welcome.download"), button -> download())
                .bounds(left, rowsTop + 70, WIDTH / 2 - 2, 20).build());
        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left, rowsTop + 70, WIDTH, 20).build());
        updateButtons();
    }

    private Catalog.Language pack() {
        LanguagePacks packs = CaptionEngine.packs();
        return packs == null ? null : packs.catalog().language(language).orElse(null);
    }

    private boolean needsDownload() {
        Catalog.Language pack = pack();
        if (pack == null) {
            return false;
        }
        LanguagePacks.State state = CaptionEngine.packs().status(pack).state();
        return state == LanguagePacks.State.AVAILABLE || state == LanguagePacks.State.FAILED;
    }

    private void updateButtons() {
        boolean needed = needsDownload();
        int left = width / 2 - WIDTH / 2;
        download.visible = needed;
        download.active = SipherClientConfig.ALLOW_DOWNLOADS.get();
        download.setTooltip(download.active ? null : Tooltip.create(Component.translatable("sipher.languages.downloads_off")));
        done.setX(needed ? left + WIDTH / 2 + 2 : left);
        done.setWidth(needed ? WIDTH / 2 - 2 : WIDTH);
    }

    @Override
    public void tick() {
        if (!packsReady && CaptionEngine.packs() != null) {
            rebuildWidgets(); // the engine finished starting: offer every language
        } else if (download != null && download.visible != needsDownload()) {
            updateButtons();
        }
    }

    private void download() {
        Catalog.Language pack = pack();
        if (pack == null) {
            return;
        }
        minecraft.setScreen(LanguagesScreen.downloadConfirmation(pack, accepted -> {
            if (accepted) {
                finish();
            } else {
                minecraft.setScreen(this);
            }
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int top = Math.max(16, height / 2 - 100);
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        text.renderCentered(graphics, width / 2, top + 20);
        graphics.drawCenteredString(font, status(), width / 2, rowsTop + 54, 0xA0A0A0);
        graphics.drawCenteredString(font, Component.translatable("sipher.welcome.footer", SipherClient.settingsKey())
                .withStyle(ChatFormatting.GRAY), width / 2, rowsTop + 100, 0xA0A0A0);
    }

    private Component status() {
        Catalog.Language pack = pack();
        if (pack == null) {
            return CaptionEngine.state() == CaptionEngine.State.FAILED
                    ? Component.translatable("sipher.status.failed", CaptionEngine.message())
                    : Component.translatable("sipher.welcome.built_in");
        }
        LanguagePacks.Status status = CaptionEngine.packs().status(pack);
        return switch (status.state()) {
            case BUILT_IN, INSTALLED -> Component.translatable("sipher.languages.installed");
            case DOWNLOADING -> Component.translatable("sipher.languages.downloading", Math.round(status.progress() * 100));
            case AVAILABLE, FAILED -> Component.translatable("sipher.welcome.needs_download", pack.name(),
                    LanguagesScreen.megabytes(CaptionEngine.packs().remainingBytes(pack)));
        };
    }

    /** Escape, Done and a confirmed download all keep what is on screen. */
    @Override
    public void onClose() {
        finish();
    }

    private void finish() {
        // Someone who already reads in a different language than they speak keeps that choice.
        if (SipherClientConfig.READING_LANGUAGE.get().equals(SipherClientConfig.SPOKEN_LANGUAGE.get())) {
            SipherClientConfig.READING_LANGUAGE.set(language);
        }
        SipherClientConfig.SPOKEN_LANGUAGE.set(language);
        SipherClientConfig.SHARE_MY_CAPTIONS.set(share);
        SipherClientConfig.WELCOME_SEEN.set(true);
        SipherClientConfig.SPEC.save();
        CaptionEngine.reconfigure();
        minecraft.setScreen(parent);
    }
}
