package com.ihanuat.mod.gui;

import java.util.ArrayList;
import java.util.List;

import com.ihanuat.mod.MacroConfig;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A collapsible section within a ClickGui panel.
 * Renders a clickable header that expands/collapses child entries.
 */
public class SectionEntry implements ClickGui.Entry {

    public static final int INDENT = 6;

    public final String label;
    public final List<ClickGui.Entry> children = new ArrayList<>();
    public boolean expanded = false;

    public SectionEntry(String label) {
        this.label = label;
    }

    /**
     * Create a section by extracting all entries from an existing Panel.
     * The original panel method stays untouched.
     */
    public static SectionEntry fromPanel(ClickGui.Panel panel) {
        SectionEntry s = new SectionEntry(panel.title);
        s.children.addAll(panel.entries);
        return s;
    }

    @Override
    public int height() {
        if (!expanded) return ClickGui.ENTRY_H;
        int h = ClickGui.ENTRY_H;
        for (ClickGui.Entry c : children) {
            h += ClickGui.ENTRY_PAD + c.height();
        }
        return h;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, boolean hov, net.minecraft.client.gui.Font font) {
        int mid = y + ClickGui.ENTRY_H / 2;
        MacroConfig.drawStyledText(g, font, (expanded ? "v " : "> ") + label, x + 2, mid - 4,
                hov ? ClickGui.C_TXT() : ClickGui.C_ACC());
        if (!expanded) return;
        int cy = y + ClickGui.ENTRY_H + ClickGui.ENTRY_PAD;
        for (ClickGui.Entry c : children) {
            int ch = c.height();
            c.render(g, x + INDENT, cy, w - INDENT, ch, false, font);
            cy += ch + ClickGui.ENTRY_PAD;
        }
    }

    @Override
    public void onClick(int mx, int my) {
        expanded = !expanded;
    }

    /** Search text includes section label + all children labels. */
    public String getSearchText() {
        StringBuilder sb = new StringBuilder(label);
        for (ClickGui.Entry c : children) {
            sb.append(' ');
            if (c instanceof ClickGui.ToggleEntry te) sb.append(te.label);
            else if (c instanceof ClickGui.SliderEntry se) sb.append(se.label);
            else if (c instanceof ClickGui.CycleEnumEntry<?> ce) sb.append(ce.label);
            else if (c instanceof ClickGui.TextSettingEntry te) sb.append(te.label);
            else if (c instanceof ClickGui.ListSettingEntry le) sb.append(le.label);
            else if (c instanceof ClickGui.IntFieldEntry ie) sb.append(ie.label);
            else if (c instanceof ClickGui.DoubleFieldEntry de) sb.append(de.label);
            else if (c instanceof ClickGui.ButtonEntry be) sb.append(be.label);
        }
        return sb.toString();
    }

    // --- Static helpers called by Panel when sections are present ---

    /** Quick check so panels without sections skip the overhead. */
    public static boolean hasSections(List<ClickGui.Entry> entries) {
        for (ClickGui.Entry e : entries) {
            if (e instanceof SectionEntry) return true;
        }
        return false;
    }

    public static int contentHeight(List<ClickGui.Entry> entries) {
        int h = ClickGui.ENTRY_PAD;
        for (ClickGui.Entry e : entries) {
            h += e.height() + ClickGui.ENTRY_PAD;
        }
        return h;
    }

    public static ClickGui.Entry entryAt(List<ClickGui.Entry> entries, int panelX, int startY, int mx, int my) {
        int ey = startY;
        for (ClickGui.Entry e : entries) {
            int eh = e.height();
            if (my >= ey && my < ey + eh && mx >= panelX && mx <= panelX + ClickGui.PANEL_W) {
                if (e instanceof SectionEntry se && se.expanded) {
                    if (my < ey + ClickGui.ENTRY_H) return se;
                    int cy = ey + ClickGui.ENTRY_H + ClickGui.ENTRY_PAD;
                    for (ClickGui.Entry c : se.children) {
                        int ch = c.height();
                        if (my >= cy && my < cy + ch) return c;
                        cy += ch + ClickGui.ENTRY_PAD;
                    }
                    return se;
                }
                return e;
            }
            ey += eh + ClickGui.ENTRY_PAD;
        }
        return null;
    }

    public static int entryY(List<ClickGui.Entry> entries, int startY, ClickGui.Entry target) {
        int ey = startY;
        for (ClickGui.Entry e : entries) {
            if (e == target) return ey;
            if (e instanceof SectionEntry se && se.expanded) {
                int cy = ey + ClickGui.ENTRY_H + ClickGui.ENTRY_PAD;
                for (ClickGui.Entry c : se.children) {
                    if (c == target) return cy;
                    cy += c.height() + ClickGui.ENTRY_PAD;
                }
            }
            ey += e.height() + ClickGui.ENTRY_PAD;
        }
        return startY;
    }

    public static boolean isChildOfSection(List<ClickGui.Entry> entries, ClickGui.Entry target) {
        for (ClickGui.Entry e : entries) {
            if (e instanceof SectionEntry se && se.expanded) {
                for (ClickGui.Entry c : se.children) {
                    if (c == target) return true;
                }
            }
        }
        return false;
    }

    public static void renderEntries(GuiGraphics g, List<ClickGui.Entry> entries, int panelX, int startY,
            int mx, int my, net.minecraft.client.gui.Font font) {
        int ey = startY;
        for (ClickGui.Entry e : entries) {
            int eh = e.height();
            if (e instanceof SectionEntry se) {
                boolean headerHov = mx >= panelX && mx <= panelX + ClickGui.PANEL_W
                        && my >= ey && my < ey + ClickGui.ENTRY_H;
                if (headerHov)
                    g.fill(panelX + 1, ey, panelX + ClickGui.PANEL_W - 1, ey + ClickGui.ENTRY_H, ClickGui.C_HOVER());
                se.render(g, panelX + ClickGui.ENTRY_PAD, ey, ClickGui.PANEL_W - ClickGui.ENTRY_PAD * 2, eh,
                        headerHov, font);
                if (se.expanded) {
                    int cy = ey + ClickGui.ENTRY_H + ClickGui.ENTRY_PAD;
                    for (ClickGui.Entry c : se.children) {
                        int ch = c.height();
                        boolean childHov = mx >= panelX && mx <= panelX + ClickGui.PANEL_W
                                && my >= cy && my < cy + ch;
                        if (childHov)
                            g.fill(panelX + 1, cy, panelX + ClickGui.PANEL_W - 1, cy + ch, ClickGui.C_HOVER());
                        c.render(g, panelX + ClickGui.ENTRY_PAD + INDENT, cy,
                                ClickGui.PANEL_W - ClickGui.ENTRY_PAD * 2 - INDENT, ch, childHov, font);
                        cy += ch + ClickGui.ENTRY_PAD;
                    }
                }
            } else {
                boolean hov = mx >= panelX && mx <= panelX + ClickGui.PANEL_W && my >= ey && my < ey + eh;
                if (hov) g.fill(panelX + 1, ey, panelX + ClickGui.PANEL_W - 1, ey + eh, ClickGui.C_HOVER());
                e.render(g, panelX + ClickGui.ENTRY_PAD, ey, ClickGui.PANEL_W - ClickGui.ENTRY_PAD * 2, eh, hov,
                        font);
            }
            ey += eh + ClickGui.ENTRY_PAD;
        }
    }
}
