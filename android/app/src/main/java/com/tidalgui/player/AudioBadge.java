package com.tidalgui.player;


final class AudioBadge {
    final String text;
    final int textColor;
    final int bgColor;
    final int borderColor;

    AudioBadge(String text, int textColor, int bgColor, int borderColor) {
        this.text = text;
        this.textColor = textColor;
        this.bgColor = bgColor;
        this.borderColor = borderColor;
    }
}
