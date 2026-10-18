package dev.daruks.mcchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client for mcchat-server.
 *
 * <p>All {@link Listener} callbacks run on the {@code main} executor (the
 * Minecraft client thread). Each connection attempt gets a generation number
 * so callbacks from an abandoned socket are ignored.
 */
public final class ChatConnection {
	public enum Status { IDLE, CONNECTING, CONNECTED, RECONNECTING, AUTH_FAILED, REVOKED }

	public record Message(long id, String channel, String name, String text, long ts) {}

	public interface Listener {
		void onStatus(Status status, String detail);
		void onHello(String name);
		void onHistory(String channel, List<Message> messages);
		void onMessage(Message message);
		void onError(String code);
	}

	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 60_000;
	private static final int CLOSE_POLICY_VIOLATION = 1008;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "mcchat-reconnect");
		t.setDaemon(true);
		return t;
	});
	private final Executor main;
	private final Listener listener;

	// Guarded by this.
	private URI uri;
	private String token;
	private String channel;
	private boolean active;
	private int generation;
	private WebSocket ws;
	private long backoffMs = MIN_BACKOFF_MS;
	private ScheduledFuture<?> pendingReconnect;
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

	public ChatConnection(Executor main, Listener listener) {
		this.main = main;
		this.listener = listener;
	}

	/** Connects (or reconnects) and joins channel. */
	public synchronized void start(URI uri, String token, String channel) {
		stop();
		this.uri = uri;
		this.token = token;
		this.channel = channel;
		this.active = true;
		this.backoffMs = MIN_BACKOFF_MS;
		connect();
	}

	/** Closes the connection and stops reconnecting. */
	public synchronized void stop() {
		active = false;
		generation++;
		if (pendingReconnect != null) {
			pendingReconnect.cancel(false);
			pendingReconnect = null;
		}
		if (ws != null) {
			WebSocket w = ws;
			ws = null;
			w.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((r, e) -> w.abort());
		}
		status(Status.IDLE, "");
	}

	public synchronized boolean connected() {
		return ws != null;
	}

	/** Queues a chat message. Returns false if not connected. */
	public synchronized boolean send(String text) {
		if (ws == null) return false;
		JsonObject o = new JsonObject();
		o.addProperty("type", "send");
		o.addProperty("text", text);
		sendJson(o);
		return true;
	}

	private void connect() {
		int gen = ++generation;
		status(Status.CONNECTING, "");
		http.newWebSocketBuilder()
				.header("Authorization", "Bearer " + token)
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(uri, new SocketListener(gen))
				.whenComplete((w, err) -> onConnected(gen, w, err));
	}

	private synchronized void onConnected(int gen, WebSocket w, Throwable err) {
		if (gen != generation) {
			if (w != null) w.abort();
			return;
		}
		if (err != null) {
			Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
			if (cause instanceof WebSocketHandshakeException h && h.getResponse().statusCode() == 401) {
				active = false;
				status(Status.AUTH_FAILED, "");
				return;
			}
			scheduleReconnect(describe(cause));
			return;
		}
		ws = w;
		backoffMs = MIN_BACKOFF_MS;
		sendChain = CompletableFuture.completedFuture(null);
		status(Status.CONNECTED, "");
		JsonObject join = new JsonObject();
		join.addProperty("type", "join");
		join.addProperty("channel", channel);
		sendJson(join);
	}

	private synchronized void onClosed(int gen, int code, String reason) {
		if (gen != generation) return;
		ws = null;
		if (code == CLOSE_POLICY_VIOLATION) {
			active = false;
			status(Status.REVOKED, reason);
			return;
		}
		scheduleReconnect(reason);
	}

	private void scheduleReconnect(String reason) {
		if (!active) return;
		ws = null;
		long delay = backoffMs;
		backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
		status(Status.RECONNECTING, reason);
		int gen = generation;
		pendingReconnect = scheduler.schedule(() -> {
			synchronized (ChatConnection.this) {
				if (active && gen == generation) connect();
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	/** WebSocket allows one outstanding send, so sends are chained. */
	private void sendJson(JsonObject o) {
		WebSocket w = ws;
		String s = o.toString();
		sendChain = sendChain.handle((r, e) -> null).thenCompose(v -> w.sendText(s, true));
	}

	private void status(Status s, String detail) {
		main.execute(() -> listener.onStatus(s, detail));
	}

	private synchronized boolean current(int gen) {
		return gen == generation;
	}

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return t.getClass().getSimpleName() + (m != null ? ": " + m : "");
	}

	private void dispatch(int gen, String text) {
		JsonObject o;
		try {
			o = JsonParser.parseString(text).getAsJsonObject();
		} catch (RuntimeException e) {
			McChatClient.LOG.warn("mcchat: bad frame {}", text);
			return;
		}
		String type = str(o, "type");
		Runnable r = switch (type) {
			case "hello" -> () -> listener.onHello(str(o, "name"));
			case "msg" -> {
				Message m = parseMessage(o);
				yield () -> listener.onMessage(m);
			}
			case "history" -> {
				List<Message> list = new ArrayList<>();
				JsonElement arr = o.get("messages");
				if (arr instanceof JsonArray a) {
					for (JsonElement e : a) {
						if (e instanceof JsonObject mo) list.add(parseMessage(mo));
					}
				}
				String ch = str(o, "channel");
				yield () -> listener.onHistory(ch, list);
			}
			case "error" -> () -> listener.onError(str(o, "code"));
			default -> null;
		};
		if (r != null) {
			main.execute(() -> {
				if (current(gen)) r.run();
			});
		}
	}

	private static Message parseMessage(JsonObject o) {
		return new Message(num(o, "id"), str(o, "channel"), str(o, "name"), str(o, "text"), num(o, "ts"));
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static long num(JsonObject o, String k) {
		JsonElement e = o.get(k);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	private final class SocketListener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buf = new StringBuilder();

		SocketListener(int gen) {
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				String s = buf.toString();
				buf.setLength(0);
				dispatch(gen, s);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			onClosed(gen, statusCode, reason);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			onClosed(gen, -1, describe(error));
		}
	}
}
