package dev.daruks.mcchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Romaji to Japanese conversion like LunaChat's Japanize: romaji is turned
 * into hiragana locally, then into kanji with Google's transliterate API.
 * If the API fails, the hiragana is used as is.
 */
public final class Japanizer {
	private static final URI GOOGLE = URI.create("https://www.google.com/transliterate");
	private static final Duration TIMEOUT = Duration.ofSeconds(3);
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private static final Map<String, String> KANA = new HashMap<>();
	private static final int MAX_KEY = 4;

	private Japanizer() {}

	/**
	 * Whether text has romaji to convert. Text with non-ASCII characters is
	 * already Japanese and is left alone.
	 */
	public static boolean shouldConvert(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) > 0x7F) return false;
		}
		return !toKanaWords(text).equals(text);
	}

	/**
	 * Converts romaji text to Japanese; completes with the hiragana if kanji
	 * conversion fails. With force, every word is converted, even ones that
	 * do not look like romaji.
	 */
	public static CompletableFuture<String> convert(String romaji, boolean force) {
		String kana = force ? toKanaForced(romaji) : toKanaWords(romaji);
		String url = GOOGLE + "?langpair=" + URLEncoder.encode("ja-Hira|ja", StandardCharsets.UTF_8)
				+ "&text=" + URLEncoder.encode(kana, StandardCharsets.UTF_8);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(res -> res.statusCode() == 200 ? firstCandidates(res.body()) : null)
				.exceptionally(e -> {
					McChatClient.LOG.warn("mcchat: kanji conversion failed: {}", e.toString());
					return null;
				})
				.thenApply(kanji -> kanji != null && !kanji.isEmpty() ? kanji : kana);
	}

	/** Joins the first candidate of each segment of [["かな", ["候補", ...]], ...]. */
	private static String firstCandidates(String body) {
		try {
			StringBuilder sb = new StringBuilder();
			for (JsonElement seg : JsonParser.parseString(body).getAsJsonArray()) {
				JsonArray s = seg.getAsJsonArray();
				JsonArray cands = s.get(1).getAsJsonArray();
				sb.append(cands.isEmpty() ? s.get(0).getAsString() : cands.get(0).getAsString());
			}
			return sb.toString();
		} catch (RuntimeException e) {
			McChatClient.LOG.warn("mcchat: unexpected transliterate response: {}", body);
			return null;
		}
	}

	/**
	 * Converts each word that is valid romaji (case-insensitive) and keeps the
	 * rest, such as "Google", "OK", "I" or URLs. Spaces between converted words
	 * are dropped since Japanese does not use them.
	 */
	static String toKanaWords(String s) {
		StringBuilder out = new StringBuilder();
		boolean prevKana = false;
		for (String word : s.trim().split(" +")) {
			if (word.isEmpty()) continue;
			String kana = wordToKana(word);
			boolean isKana = kana != null;
			if (!out.isEmpty() && !(prevKana && isKana)) out.append(' ');
			out.append(isKana ? kana : word);
			prevKana = isKana;
		}
		return out.toString();
	}

	/** Converts the whole text, lowercased, with spaces dropped. */
	static String toKanaForced(String s) {
		return toKana(s.toLowerCase().replaceAll(" +", ""));
	}

	/** Hiragana for word, or null if it is not romaji. */
	private static String wordToKana(String word) {
		if (word.contains("://") || word.equals(word.toUpperCase()) && !word.equals(word.toLowerCase())) return null;
		String kana = toKana(word.toLowerCase());
		for (int i = 0; i < kana.length(); i++) {
			char c = kana.charAt(i);
			if (c >= 'a' && c <= 'z') return null;
		}
		return kana;
	}

	/** Romaji to hiragana with the usual IME rules (nn, small tsu on doubled consonants, xa/la). */
	static String toKana(String s) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
			// Doubled consonant: "tta" -> "った".
			if (c == next && isConsonant(c) && c != 'n') {
				out.append('っ');
				i++;
				continue;
			}
			if (c == 'n') {
				char after = i + 2 < s.length() ? s.charAt(i + 2) : 0;
				// "n" before a consonant or at the end: "kanji" -> "かんじ", "n'a" -> "んあ".
				// "nn" before a vowel keeps the second n: "konnichiha" -> "こんにちは".
				if (next != 'n' && next != 'y' && !isVowel(next)
						|| next == 'n' && (isVowel(after) || after == 'y')) {
					out.append('ん');
					i += next == '\'' ? 2 : 1;
					continue;
				}
			}
			String hit = null;
			int len = Math.min(MAX_KEY, s.length() - i);
			for (; len > 0; len--) {
				hit = KANA.get(s.substring(i, i + len));
				if (hit != null) break;
			}
			if (hit != null) {
				out.append(hit);
				i += len;
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static boolean isVowel(char c) {
		return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o';
	}

	private static boolean isConsonant(char c) {
		return c >= 'a' && c <= 'z' && !isVowel(c);
	}

	private static void row(String consonant, String kana) {
		String[] vowels = {"a", "i", "u", "e", "o"};
		String[] k = kana.split(" ");
		for (int i = 0; i < 5; i++) {
			if (!k[i].equals("-")) KANA.put(consonant + vowels[i], k[i]);
		}
	}

	static {
		row("", "あ い う え お");
		row("k", "か き く け こ");
		row("s", "さ し す せ そ");
		row("t", "た ち つ て と");
		row("n", "な に ぬ ね の");
		row("h", "は ひ ふ へ ほ");
		row("m", "ま み む め も");
		row("y", "や - ゆ いぇ よ");
		row("r", "ら り る れ ろ");
		row("w", "わ うぃ う うぇ を");
		row("g", "が ぎ ぐ げ ご");
		row("z", "ざ じ ず ぜ ぞ");
		row("d", "だ ぢ づ で ど");
		row("b", "ば び ぶ べ ぼ");
		row("p", "ぱ ぴ ぷ ぺ ぽ");
		row("v", "ゔぁ ゔぃ ゔ ゔぇ ゔぉ");
		row("f", "ふぁ ふぃ ふ ふぇ ふぉ");
		row("j", "じゃ じ じゅ じぇ じょ");
		row("l", "ぁ ぃ ぅ ぇ ぉ");
		row("x", "ぁ ぃ ぅ ぇ ぉ");
		row("q", "くぁ くぃ く くぇ くぉ");
		row("c", "か し く せ こ");
		row("ky", "きゃ きぃ きゅ きぇ きょ");
		row("sy", "しゃ しぃ しゅ しぇ しょ");
		row("sh", "しゃ し しゅ しぇ しょ");
		row("ty", "ちゃ ちぃ ちゅ ちぇ ちょ");
		row("ch", "ちゃ ち ちゅ ちぇ ちょ");
		row("cy", "ちゃ ちぃ ちゅ ちぇ ちょ");
		row("ts", "つぁ つぃ つ つぇ つぉ");
		row("th", "てゃ てぃ てゅ てぇ てょ");
		row("dh", "でゃ でぃ でゅ でぇ でょ");
		row("tw", "とぁ とぃ とぅ とぇ とぉ");
		row("dw", "どぁ どぃ どぅ どぇ どぉ");
		row("ny", "にゃ にぃ にゅ にぇ にょ");
		row("hy", "ひゃ ひぃ ひゅ ひぇ ひょ");
		row("my", "みゃ みぃ みゅ みぇ みょ");
		row("ry", "りゃ りぃ りゅ りぇ りょ");
		row("gy", "ぎゃ ぎぃ ぎゅ ぎぇ ぎょ");
		row("zy", "じゃ じぃ じゅ じぇ じょ");
		row("jy", "じゃ じぃ じゅ じぇ じょ");
		row("dy", "ぢゃ ぢぃ ぢゅ ぢぇ ぢょ");
		row("by", "びゃ びぃ びゅ びぇ びょ");
		row("py", "ぴゃ ぴぃ ぴゅ ぴぇ ぴょ");
		row("fy", "ふゃ ふぃ ふゅ ふぇ ふょ");
		row("vy", "ゔゃ ゔぃ ゔゅ ゔぇ ゔょ");
		row("ly", "ゃ ぃ ゅ ぇ ょ");
		row("xy", "ゃ ぃ ゅ ぇ ょ");
		KANA.put("si", "し");
		KANA.put("ti", "ち");
		KANA.put("tu", "つ");
		KANA.put("hu", "ふ");
		KANA.put("zi", "じ");
		KANA.put("di", "ぢ");
		KANA.put("du", "づ");
		KANA.put("nn", "ん");
		KANA.put("ltu", "っ");
		KANA.put("xtu", "っ");
		KANA.put("ltsu", "っ");
		KANA.put("lwa", "ゎ");
		KANA.put("xwa", "ゎ");
		KANA.put("lka", "ゕ");
		KANA.put("lke", "ゖ");
		KANA.put("-", "ー");
		KANA.put(",", "、");
		KANA.put(".", "。");
		KANA.put("!", "！");
		KANA.put("?", "？");
		KANA.put("[", "「");
		KANA.put("]", "」");
		KANA.put("~", "～");
	}
}
