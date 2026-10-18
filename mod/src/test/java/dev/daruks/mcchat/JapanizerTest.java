package dev.daruks.mcchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JapanizerTest {
	@Test
	void toKana() {
		assertEquals("おはようございます！", Japanizer.toKana("ohayougozaimasu!"));
		assertEquals("こんにちは", Japanizer.toKana("konnnitiha"));
		assertEquals("こんにちは", Japanizer.toKana("konnichiha"));
		assertEquals("みんな", Japanizer.toKana("minna"));
		assertEquals("かんじ", Japanizer.toKana("kanji"));
		assertEquals("きって", Japanizer.toKana("kitte"));
		assertEquals("まっちゃ", Japanizer.toKana("maccha"));
		assertEquals("しんよう", Japanizer.toKana("shin'you"));
		assertEquals("ほん", Japanizer.toKana("hon"));
		assertEquals("ぎゃー、すごい。", Japanizer.toKana("gya-,sugoi."));
		assertEquals("てぃっしゅ 2こ", Japanizer.toKana("thisshu 2ko"));
	}

	@Test
	void shouldConvert() {
		assertTrue(Japanizer.shouldConvert("ohayou"));
		assertFalse(Japanizer.shouldConvert("おはよう"));
		assertFalse(Japanizer.shouldConvert("OK"));
		assertFalse(Japanizer.shouldConvert("see https://example.com"));
		assertFalse(Japanizer.shouldConvert("123"));
	}
}
