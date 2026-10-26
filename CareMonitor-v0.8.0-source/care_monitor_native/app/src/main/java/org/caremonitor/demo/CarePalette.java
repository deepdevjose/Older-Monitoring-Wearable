package org.caremonitor.demo;

import android.graphics.Color;

/** Colors shared by the Clear care and Health focus themes. */
public final class CarePalette {
    public final int background;
    public final int surface;
    public final int text;
    public final int muted;
    public final int border;
    public final int primary;
    public final int primaryText;
    public final int soft;
    public final int heart;
    public final int oxygen;
    public final int surfaceTemp;
    public final int ambientTemp;
    public final int warning;
    public final int warningSoft;
    public final int danger;

    public CarePalette(boolean dark) {
        background = color(dark ? "#0C1D16" : "#F3F7F5");
        surface = color(dark ? "#182D22" : "#FFFFFF");
        text = color(dark ? "#F0F5EF" : "#193D35");
        muted = color(dark ? "#B2C1B7" : "#62766F");
        border = color(dark ? "#354B3E" : "#E0E9E3");
        primary = color(dark ? "#B5DFAB" : "#126B57");
        primaryText = color(dark ? "#13331D" : "#FFFFFF");
        soft = color(dark ? "#304934" : "#E2F0E7");
        heart = color(dark ? "#EF9DBA" : "#B84C78");
        oxygen = color(dark ? "#94C8EB" : "#367CAA");
        surfaceTemp = color(dark ? "#F4BB73" : "#B56A26");
        ambientTemp = color(dark ? "#88C5EE" : "#2875A8");
        warning = color(dark ? "#F4C87A" : "#A5661B");
        warningSoft = color(dark ? "#463820" : "#FFF1D9");
        danger = color(dark ? "#F2A29C" : "#B34D4B");
    }

    private static int color(String hex) {
        return Color.parseColor(hex);
    }
}
