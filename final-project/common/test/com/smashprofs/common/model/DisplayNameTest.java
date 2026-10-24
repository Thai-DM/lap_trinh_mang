package com.smashprofs.common.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayNameTest {

    @Test public void keepsTwelveCharsAndCutsTheRest() {
        assertEquals("ChampionAlex",DisplayName.sanitize("ChampionAlex",null));
        assertEquals("ChampionAlex",DisplayName.sanitize("ChampionAlexander",null));
    }

    @Test public void blankOrNullUsesFallback() {
        assertEquals("Alex",DisplayName.sanitize("   \t\n","Alex"));
        assertNull(DisplayName.sanitize(null,null));
    }

    @Test public void collapsesWhitespaceAndStripsControl() { assertEquals("a b c",DisplayName.sanitize("  a \t b\nc  ",null)); }

    @Test public void neverSplitsSurrogatePairs() {
        // 14 emoji (each is 1 code point, 2 chars as surrogate pair) → should cut to 12 code points
        String emoji14 = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00";
        String cut=DisplayName.sanitize(emoji14,null);
        assertEquals(12,cut.codePointCount(0,cut.length()));assertFalse(Character.isLowSurrogate(cut.charAt(0)));
    }

    @Test public void keepsVietnamese() { assertEquals("Nguy\u1ec5n Vi\u1ec7t",DisplayName.sanitize("  Nguy\u1ec5n   Vi\u1ec7t ",null)); }

    @Test public void maxLengthIsTwelve() { assertEquals(12,DisplayName.MAX_LENGTH); }
}
