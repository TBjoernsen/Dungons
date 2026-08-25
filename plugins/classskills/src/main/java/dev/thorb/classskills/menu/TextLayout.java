package dev.thorb.classskills.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Pixel-width helpers used to align the vanilla-font dialog stat rows. */
final class TextLayout {
    private static final int DEFAULT_WIDTH = 6;
    private static final int SPACE_WIDTH = 4;
    private static final int DOT_WIDTH = 2;
    private static final int[] ASCII = new int[128];

    static {
        for (int i = 0; i < ASCII.length; i++) ASCII[i] = DEFAULT_WIDTH;
        ASCII[' '] = 4; ASCII['!'] = 2; ASCII['"'] = 5; ASCII['\''] = 3;
        ASCII['('] = 5; ASCII[')'] = 5; ASCII['*'] = 5; ASCII[','] = 2;
        ASCII['.'] = 2; ASCII['/'] = 6; ASCII[':'] = 2; ASCII[';'] = 2;
        ASCII['<'] = 5; ASCII['>'] = 5; ASCII['@'] = 7; ASCII['I'] = 4;
        ASCII['['] = 4; ASCII[']'] = 4; ASCII['`'] = 3; ASCII['f'] = 5;
        ASCII['i'] = 2; ASCII['k'] = 5; ASCII['l'] = 3; ASCII['t'] = 4;
        ASCII['{'] = 5; ASCII['|'] = 2; ASCII['}'] = 5; ASCII['~'] = 7;
    }

    private TextLayout() { }

    static int width(String text) {
        int total = 0;
        for (char c : text.toCharArray()) total += c < ASCII.length ? ASCII[c] : DEFAULT_WIDTH;
        return total;
    }

    static Component leaderRow(String label, String value, int targetWidth, TextColor labelColor, TextColor valueColor) {
        int consumed = width(label) + width(value) + (SPACE_WIDTH * 2);
        int dots = Math.max(2, (targetWidth - consumed) / DOT_WIDTH);
        return Component.text(label, labelColor)
                .append(Component.text(" " + ".".repeat(dots) + " ", NamedTextColor.DARK_GRAY))
                .append(Component.text(value, valueColor));
    }

    static Component heading(String text, int targetWidth, TextColor from, TextColor to) {
        int padding = Math.max(0, (targetWidth - width(text)) / 2 / SPACE_WIDTH);
        String pad = " ".repeat(padding);
        return Component.text(pad).append(gradient(text, from, to)).append(Component.text(pad));
    }

    private static Component gradient(String text, TextColor from, TextColor to) {
        Component result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            float fraction = text.length() <= 1 ? 0F : (float) i / (text.length() - 1);
            result = result.append(Component.text(text.charAt(i), TextColor.lerp(fraction, from, to)));
        }
        return result;
    }
}
