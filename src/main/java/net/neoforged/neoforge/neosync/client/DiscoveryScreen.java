/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.neosync.client;

import com.mojang.blaze3d.vertex.Tesselator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.gui.widget.ScrollPanel;
import org.jetbrains.annotations.Nullable;

final class DiscoveryScreen extends Screen {
    private final Screen parent;
    private final Runnable cancel;
    private List<String> paragraphs = List.of("Checking this server's mod requirements...");
    @Nullable
    private Runnable action;
    private String actionLabel = "";
    private String backLabel = "Cancel";
    private int presentation;
    @Nullable
    private java.util.function.Consumer<String> selectedPath;
    @Nullable
    private EditBox pathInput;
    private String pathText = "";
    @Nullable
    private Button negativeAction;

    DiscoveryScreen(Screen parent, Runnable cancel) {
        super(Component.literal("NeoSync"));
        this.parent = parent;
        this.cancel = cancel;
    }

    void show(List<String> paragraphs, String backLabel, String actionLabel, @Nullable Runnable action) {
        selectedPath = null;
        pathInput = null;
        this.paragraphs = List.copyOf(paragraphs);
        this.backLabel = backLabel;
        this.actionLabel = actionLabel;
        this.action = action;
        presentation++;
        rebuildWidgets();
    }

    void showManual(List<String> paragraphs, java.util.function.Consumer<String> selectedPath, Runnable reopen) {
        if (pathInput != null) pathText = pathInput.getValue();
        show(paragraphs, "Cancel", "Open CurseForge page", reopen);
        this.selectedPath = selectedPath;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(180, width / 2 - 24);
        var back = addRenderableWidget(Button.builder(Component.literal(backLabel), button -> onClose())
                .bounds(width / 2 - buttonWidth - 4, height - 30, buttonWidth, 20).build());
        negativeAction = back;
        if (action != null) {
            addRenderableWidget(Button.builder(Component.literal(actionLabel), button -> {
                Runnable selected = action;
                action = null;
                button.active = false;
                if (selected != null) selected.run();
            }).bounds(width / 2 + 4, height - 30, buttonWidth, 20).build());
        }
        if (selectedPath != null) {
            pathInput = new EditBox(font, 20, height - 78, Math.max(60, width - 140), 20, Component.literal("Downloaded file or folder path"));
            pathInput.setMaxLength(4096);
            pathInput.setHint(Component.literal("Downloaded file or folder path"));
            pathInput.setValue(pathText);
            addRenderableWidget(pathInput);
            addRenderableWidget(Button.builder(Component.literal("Use path"), button -> {
                if (this.selectedPath != null && pathInput != null) this.selectedPath.accept(pathInput.getValue());
            }).bounds(width - 112, height - 78, 92, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Choose downloaded file..."), button -> {
                try {
                    String chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Choose the downloaded mod", (CharSequence) null, null, null, false);
                    if (chosen != null && this.selectedPath != null) this.selectedPath.accept(chosen);
                } catch (RuntimeException | LinkageError e) {
                    if (pathInput != null) pathInput.setHint(Component.literal("Enter the full downloaded file path"));
                }
            }).bounds(20, height - 54, Math.min(220, width - 40), 20).build());
        }
        setInitialFocus(back);
        addRenderableWidget(new TextPanel());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int previous = presentation;
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // Mouse dispatch restores focus to the clicked widget after its action, even if the action replaced that widget.
        if (previous != presentation && negativeAction != null) setInitialFocus(negativeAction);
        return handled;
    }

    @Override
    public void onClose() {
        cancel.run();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        cancel.run();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
    }

    private final class TextPanel extends ScrollPanel {
        private final List<FormattedCharSequence> lines;

        TextPanel() {
            super(DiscoveryScreen.this.minecraft, DiscoveryScreen.this.width - 32, Math.max(20, DiscoveryScreen.this.height - (selectedPath == null ? 82 : 134)), 38, 16);
            lines = paragraphs.stream().flatMap(text -> font.split(Component.literal(text.isEmpty() ? " " : text), Math.max(20, width - 24)).stream()).toList();
        }

        @Override
        protected int getContentHeight() {
            return Math.max(1, lines.size()) * (font.lineHeight + 3);
        }

        @Override
        protected void drawPanel(GuiGraphics graphics, int entryRight, int relativeY, Tesselator tessellator, int mouseX, int mouseY) {
            int lineHeight = font.lineHeight + 3;
            int first = Math.max(0, (top - relativeY) / lineHeight);
            int last = Math.min(lines.size(), first + height / lineHeight + 2);
            for (int i = first; i < last; i++) graphics.drawString(font, lines.get(i), left + 6, relativeY + i * lineHeight, 0xFFFFFF);
        }

        @Override
        public NarrationPriority narrationPriority() {
            return NarrationPriority.HOVERED;
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(String.join(". ", paragraphs.subList(0, Math.min(5, paragraphs.size())))));
        }
    }
}
