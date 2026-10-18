package dev.daruks.mcchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
	void dictionary() {
		Map<String, String> dict = Map.of("maikura", "マイクラ", "mai", "舞");
		assertEquals(List.of(
				new Japanizer.Segment("kyou ", false),
				new Japanizer.Segment("マイクラ", true),
				new Japanizer.Segment(" yarou", false)), Japanizer.split("kyou Maikura yarou", dict));
		assertTrue(Japanizer.shouldConvert("Maikura", dict));
		assertFalse(Japanizer.shouldConvert("Google", dict));
	}

	@Test
	void shouldConvert() {
		assertTrue(Japanizer.shouldConvert("ohayou", Map.of()));
		assertTrue(Japanizer.shouldConvert("Shikashi Google wa", Map.of()));
		assertFalse(Japanizer.shouldConvert("おはよう", Map.of()));
		assertFalse(Japanizer.shouldConvert("OK", Map.of()));
		assertFalse(Japanizer.shouldConvert("Google", Map.of()));
		assertFalse(Japanizer.shouldConvert("https://example.com", Map.of()));
		assertFalse(Japanizer.shouldConvert("123", Map.of()));
	}
}
