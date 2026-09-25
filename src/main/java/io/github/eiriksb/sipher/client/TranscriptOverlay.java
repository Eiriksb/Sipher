package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.config.SipherClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD box with recent captions ("Name: text"). It hides itself a while after the last caption, and can be dragged
 * while the chat screen is open.
 */
public final class TranscriptOverlay {
    private static final int PADDING = 5;
    private static final int HEADER = 12;
    private static final long HIDE_AFTER_IDLE_MS = 20_000;
    private static final Component TITLE = Component.translatable("sipher.transcript.title");
    private static final Component LISTENING = Component.translatable("sipher.transcript.listening");

    private static int boxX;
    private static int boxY;
    private static int boxWidth;
    private static int boxHeight;
    private static boolean dragging;
    private static int dragOffsetX;
    private static int dragOffsetY;

    private TranscriptOverlay() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean editing = minecraft.screen instanceof ChatScreen;
        if (!SipherClientConfig.TRANSCRIPT_ENABLED.get() || minecraft.options.hideGui
                || (!editing && CaptionLog.idleMillis() > HIDE_AFTER_IDLE_MS)) {
            boxWidth = 0;
            return;
        }

        Font font = minecraft.font;
        int width = Math.min(SipherClientConfig.TRANSCRIPT_WIDTH.get(), graphics.guiWidth() - 8);
        List<Row> rows = layout(font, width - PADDING * 2);
        int height = HEADER + PADDING + Math.max(1, rows.size()) * (font.lineHeight + 1) + PADDING;
        int x = Math.clamp(SipherClientConfig.TRANSCRIPT_X.get(), 0, Math.max(0, graphics.guiWidth() - width));
        int y = Math.clamp(SipherClientConfig.TRANSCRIPT_Y.get(), 0, Math.max(0, graphics.guiHeight() - height));
        boxX = x;
        boxY = y;
        boxWidth = width;
        boxHeight = height;

        graphics.fill(x, y, x + width, y + height, editing ? 0xCC101316 : 0x88101316);
        if (editing) {
            graphics.renderOutline(x, y, width, height, dragging ? 0xFF6CCBFF : 0xAA6CCBFF);
        }
        graphics.drawString(font, TITLE, x + PADDING, y + 3, 0xFFB8C4D0, false);

        int textY = y + HEADER + PADDING;
        if (rows.isEmpty()) {
            graphics.drawString(font, LISTENING, x + PADDING, textY, 0xFF9CA3AF, false);
            return;
        }
        for (Row row : rows) {
            graphics.drawString(font, row.text(), x + PADDING, textY, row.partial() ? 0xFFB8F7D4 : 0xFFFFFFFF, false);
            textY += font.lineHeight + 1;
        }
    }

    private static List<Row> layout(Font font, int width) {
        int maxRows = SipherClientConfig.TRANSCRIPT_LINES.get();
        List<Row> rows = new ArrayList<>();
        for (CaptionLog.Entry entry : CaptionLog.recent(maxRows)) {
            Component line = Component.literal(entry.name() + ": ").withStyle(style -> style.withColor(0x9CC3FF))
                    .append(Component.literal(entry.text()));
            for (FormattedCharSequence wrapped : font.split(line, width)) {
                rows.add(new Row(wrapped, entry.partial()));
            }
        }
        return rows.size() > maxRows ? rows.subList(rows.size() - maxRows, rows.size()) : rows;
    }

    public static boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || boxWidth == 0 || !(Minecraft.getInstance().screen instanceof ChatScreen)
                || mouseX < boxX || mouseX > boxX + boxWidth || mouseY < boxY || mouseY > boxY + boxHeight) {
            return false;
        }
        dragging = true;
        dragOffsetX = (int) mouseX - boxX;
        dragOffsetY = (int) mouseY - boxY;
        return true;
    }

    public static boolean mouseDragged(double mouseX, double mouseY) {
        if (!dragging) {
            return false;
        }
        SipherClientConfig.TRANSCRIPT_X.set(Math.max(0, (int) mouseX - dragOffsetX));
        SipherClientConfig.TRANSCRIPT_Y.set(Math.max(0, (int) mouseY - dragOffsetY));
        return true;
    }

    public static boolean mouseReleased() {
        if (!dragging) {
            return false;
        }
        dragging = false;
        SipherClientConfig.SPEC.save();
        return true;
    }

    private record Row(FormattedCharSequence text, boolean partial) {
    }
}
