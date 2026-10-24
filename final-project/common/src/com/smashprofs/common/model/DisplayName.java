package com.smashprofs.common.model;

/** Single source of truth for player-visible names. Pure Java, safe for TeaVM and headless tests. */
public final class DisplayName {
    public static final int MAX_LENGTH=12;
    private DisplayName() { }
    /** Trims, turns control/whitespace runs into one space, cuts to MAX_LENGTH code points. Blank result returns fallback. */
    public static String sanitize(String raw,String fallback) {
        if(raw==null) { return fallback; }
        var out=new StringBuilder(MAX_LENGTH*2);int points=0;boolean pendingSpace=false;
        for(int i=0;i<raw.length();) {
            int cp=raw.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isWhitespace(cp) || Character.isISOControl(cp)) { pendingSpace=out.length()>0;continue; }
            int need=pendingSpace?2:1;
            if(points+need>MAX_LENGTH) { break; }
            if(pendingSpace) { out.append(' ');points++;pendingSpace=false; }
            out.appendCodePoint(cp);points++;
        }
        return out.length()==0?fallback:out.toString();
    }
}
