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
	void toKanaWords() {
		assertEquals("Google わ、こうしたりようきやくわできれば", Japanizer.toKanaWords("Google wa, koushita riyou kiyaku wa dekireba"));
		assertEquals("しかし、", Japanizer.toKanaWords("Shikashi,"));
		assertEquals("OK です", Japanizer.toKanaWords("OK desu"));
		assertEquals("I", Japanizer.toKanaWords("I"));
		assertEquals("これ https://example.com みて", Japanizer.toKanaWords("kore https://example.com mite"));
	}

	@Test
	void toKanaForced() {
		assertEquals("ごおgぇ", Japanizer.toKanaForced("Google"));
		assertEquals("しかしおk", Japanizer.toKanaForced("Shikashi OK"));
	}

	@Test
	void shouldConvert() {
		assertTrue(Japanizer.shouldConvert("ohayou"));
		assertTrue(Japanizer.shouldConvert("Shikashi Google wa"));
		assertFalse(Japanizer.shouldConvert("おはよう"));
		assertFalse(Japanizer.shouldConvert("OK"));
		assertFalse(Japanizer.shouldConvert("Google"));
		assertFalse(Japanizer.shouldConvert("https://example.com"));
		assertFalse(Japanizer.shouldConvert("123"));
	}
}
