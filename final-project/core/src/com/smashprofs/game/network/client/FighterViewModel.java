package com.smashprofs.game.network.client;

/** Presentation policy shared by HUD and its pure tests. */
public final class FighterViewModel {
    private FighterViewModel() { }
    public static int damageBand(double percent) { return percent>160?4:percent>120?3:percent>80?2:percent>40?1:0; }
    public static String damageText(double percent) { return String.format(java.util.Locale.ROOT,"%.0f%%",percent); }
    public static String blastEdge(String cause) { return switch(cause) { case "BLAST_LEFT"->"biên trái";case "BLAST_RIGHT"->"biên phải";case "BLAST_TOP"->"biên trên";default->"vực dưới"; }; }
}
