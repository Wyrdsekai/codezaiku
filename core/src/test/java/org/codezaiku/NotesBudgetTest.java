package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The synthesis notes budget counts TOKENS by script — CJK text is ~1 token per character. */
class NotesBudgetTest {

    @Test
    void cjkCountsPerCharacterLatinPerFourChars() {
        assertEquals(12, FamiliarMain.estTokens("字幕翻訳で失われる要素は"));   // 12 CJK chars, 12 tokens
        assertEquals(10, FamiliarMain.estTokens("a".repeat(40)));
        assertEquals(0, FamiliarMain.estTokens(null));
    }

    @Test
    void notesFitTheWindow() {
        String ja = "敬語".repeat(3000);      // 6000 tokens of CJK
        String en = "word ".repeat(3000);    // ~3750 tokens
        String notes = FamiliarMain.fitNotes(List.of(ja, en, ja, en, ja, en, ja), 32768);
        int budget = (int) (32768 * 0.35);
        assertTrue(FamiliarMain.estTokens(notes) <= budget + 50, "fit: " + FamiliarMain.estTokens(notes));
        assertTrue(notes.contains("trimmed to fit"), "over-budget notes are trimmed, visibly");
        // small notes pass through untouched
        assertEquals("short\n\nnotes", FamiliarMain.fitNotes(List.of("short", "notes"), 32768));
    }
}
