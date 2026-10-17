package dev.daruks.mcchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs ChatConnection against a real mcchat-server. Skipped unless
 * MCCHAT_IT_URL, MCCHAT_IT_TOKEN_A and MCCHAT_IT_TOKEN_B are set.
 */
class ChatConnectionIT {
	private URI uri;
	private String tokenA;
	private String tokenB;

	@BeforeEach
	void env() {
		String url = System.getenv("MCCHAT_IT_URL");
		tokenA = System.getenv("MCCHAT_IT_TOKEN_A");
		tokenB = System.getenv("MCCHAT_IT_TOKEN_B");
		assumeTrue(url != null && !url.isEmpty() && tokenA != null && !tokenA.isEmpty(), "no mcchat-server configured");
		uri = URI.create(url);
	}

	/** Records every callback as a string event. */
	static final class Recorder implements ChatConnection.Listener {
		final BlockingQueue<String> events = new LinkedBlockingQueue<>();

		public void onStatus(ChatConnection.Status s, String d) { events.add("status:" + s); }
		public void onHello(String name) { events.add("hello:" + name); }
		public void onHistory(String ch, List<ChatConnection.Message> m) { events.add("history:" + ch + ":" + m.size()); }
		public void onMessage(ChatConnection.Message m) { events.add("msg:" + m.channel() + ":" + m.name() + ":" + m.text()); }
		public void onError(String code) { events.add("error:" + code); }

		/** Waits for an event starting with prefix, skipping others. */
		String await(String prefix) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				String e = events.poll(100, TimeUnit.MILLISECONDS);
				if (e != null && e.startsWith(prefix)) return e;
			}
			return null;
		}
	}

	@Test
	void sendAndReceiveWithinChannel() throws Exception {
		String ch = "it-" + System.nanoTime() + ".example.com";
		Recorder ra = new Recorder(), rb = new Recorder(), rc = new Recorder();
		ChatConnection a = new ChatConnection(Runnable::run, ra);
		ChatConnection b = new ChatConnection(Runnable::run, rb);
		ChatConnection c = new ChatConnection(Runnable::run, rc);
		try {
			a.start(uri, tokenA, ch.toUpperCase() + ":25565");
			b.start(uri, tokenB, ch);
			c.start(uri, tokenB, "other-" + ch);
			assertEquals("history:" + ch + ":0", ra.await("history:"));
			assertEquals("history:" + ch + ":0", rb.await("history:"));
			assertNotNull(rc.await("history:"));

			assertTrue(a.send("こんにちは\nworld"));
			String got = rb.await("msg:");
			assertNotNull(got);
			assertTrue(got.startsWith("msg:" + ch + ":") && got.endsWith(":こんにちは world"), got);
			assertNotNull(ra.await("msg:"), "sender gets its own echo");
			assertNull(rc.await("msg:"), "other channel must not receive");
		} finally {
			a.stop();
			b.stop();
			c.stop();
		}
	}

	@Test
	void badTokenReportsAuthFailedWithoutRetry() throws Exception {
		Recorder r = new Recorder();
		ChatConnection conn = new ChatConnection(Runnable::run, r);
		try {
			conn.start(uri, "mcc_invalid", "example.com");
			assertEquals("status:AUTH_FAILED", r.await("status:AUTH_FAILED"));
			assertNull(r.await("status:CONNECTING"), "must not retry after 401");
			assertTrue(!conn.send("x"));
		} finally {
			conn.stop();
		}
	}

	@Test
	void stopPreventsReconnect() throws Exception {
		Recorder r = new Recorder();
		ChatConnection conn = new ChatConnection(Runnable::run, r);
		conn.start(uri, tokenA, "example.com");
		assertNotNull(r.await("history:"));
		conn.stop();
		assertNull(r.await("status:CONNECTING"));
		assertTrue(!conn.send("x"));
	}

	@Test
	void unreachableServerKeepsRetrying() throws Exception {
		Recorder r = new Recorder();
		ChatConnection conn = new ChatConnection(Runnable::run, r);
		try {
			conn.start(URI.create("ws://127.0.0.1:1/ws"), tokenA, "example.com");
			assertNotNull(r.await("status:RECONNECTING"));
			assertNotNull(r.await("status:CONNECTING"), "retries after backoff");
		} finally {
			conn.stop();
		}
	}
}
