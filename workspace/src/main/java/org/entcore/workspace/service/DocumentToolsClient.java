package org.entcore.workspace.service;

import fr.wseduc.webutils.collections.SharedDataHelper;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Client des routes {@code POST /tools/<op>} du service de documents (node-pdf-generator, 2.2.0 et
 * suivantes) : fusion, pages, mot de passe, aplatissement, rastérisation, compression, conversions.
 * Même configuration que le générateur PDF du socle ({@code node-pdf-generator} : {@code url},
 * {@code auth}), lue dans la configuration du module ou, à défaut, dans celle du serveur.
 * Les options (mots de passe compris) partent dans le corps de la requête, jamais dans l'URL.
 */
public class DocumentToolsClient {

	private static final Logger log = LoggerFactory.getLogger(DocumentToolsClient.class);

	/** Fichier envoyé au service : seul son extension compte, le nom d'origine ne sort pas. */
	public static class Input {
		final Buffer content;
		final String extension;

		public Input(Buffer content, String extension) {
			this.content = content;
			this.extension = extension == null ? "" : extension.replaceAll("[^a-z0-9]", "");
		}
	}

	/** Fichier produit par le service. */
	public static class Result {
		public final Buffer content;
		public final String contentType;
		public final String extension;

		Result(Buffer content, String contentType, String extension) {
			this.content = content;
			this.contentType = contentType;
			this.extension = extension;
		}
	}

	/** Refus du service : statut HTTP et clé de traduction ({@code workspace.transform.*}). */
	public static class ToolException extends RuntimeException {
		public final int status;
		public final String code;

		public ToolException(int status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}
	}

	private HttpClient client;
	private String authHeader;
	private long timeoutMs;

	public DocumentToolsClient(Vertx vertx, JsonObject config) {
		SharedDataHelper.getInstance().<String, String>getLocalMulti("server", "node-pdf-generator").onSuccess(serverMap -> {
			JsonObject node = null;
			final String fromServer = serverMap.get("node-pdf-generator");
			if (fromServer != null) {
				node = new JsonObject(fromServer);
			}
			if (config != null && config.getJsonObject("node-pdf-generator") != null) {
				node = config.getJsonObject("node-pdf-generator");
			}
			if (node == null || node.getString("url") == null) {
				log.warn("[DocumentToolsClient] node-pdf-generator non configuré : transformations indisponibles");
				return;
			}
			try {
				final URI uri = new URI(node.getString("url"));
				this.authHeader = "Basic " + node.getString("auth", "");
				this.timeoutMs = node.getLong("tools-timeout", 180_000L);
				this.client = vertx.createHttpClient(new HttpClientOptions()
						.setMaxPoolSize(node.getInteger("tools-pool", 8))
						.setDefaultHost(uri.getHost())
						.setDefaultPort(uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80))
						.setSsl("https".equals(uri.getScheme())));
			} catch (Exception e) {
				log.error("[DocumentToolsClient] adresse du service invalide", e);
			}
		}).onFailure(e -> log.error("[DocumentToolsClient] configuration node-pdf-generator illisible", e));
	}

	public boolean isAvailable() {
		return client != null;
	}

	/** Lance l'opération {@code op} ; {@code info} rend un JSON (voir {@link #info}). */
	public Future<Result> apply(String op, List<Input> files, JsonObject options) {
		if (client == null) {
			return Future.failedFuture(new ToolException(503, "workspace.transform.unavailable", "service indisponible"));
		}
		final String boundary = UUID.randomUUID().toString();
		final Buffer body = Buffer.buffer();
		for (int i = 0; i < files.size(); i++) {
			final Input f = files.get(i);
			body.appendString("--" + boundary + "\r\n")
					.appendString("Content-Disposition: form-data; name=\"file" + i + "\"; filename=\"f" + i + "." + f.extension + "\"\r\n")
					.appendString("Content-Type: application/octet-stream\r\n\r\n")
					.appendBuffer(f.content)
					.appendString("\r\n");
		}
		body.appendString("--" + boundary + "\r\n")
				.appendString("Content-Disposition: form-data; name=\"options\"\r\n")
				.appendString("Content-Type: application/json; charset=utf-8\r\n\r\n")
				.appendString((options == null ? new JsonObject() : options).encode(), "UTF-8")
				.appendString("\r\n--" + boundary + "--\r\n");

		final Promise<Result> promise = Promise.promise();
		client.request(new RequestOptions()
						.setMethod(HttpMethod.POST)
						.setURI("/tools/" + op)
						.setIdleTimeout(timeoutMs)
						.addHeader("Authorization", authHeader)
						.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary))
				.compose(req -> req.send(body))
				.compose(resp -> resp.body().map(buf -> {
					if (resp.statusCode() == 200) {
						return new Result(buf, resp.getHeader("Content-Type"), resp.getHeader("X-Result-Extension"));
					}
					String code = "workspace.transform.failed";
					String message = "";
					try {
						final JsonObject err = buf.toJsonObject();
						code = err.getString("error", code);
						message = err.getString("message", "");
					} catch (Exception e) {
						message = buf.toString().substring(0, Math.min(200, buf.length()));
					}
					throw new ToolException(resp.statusCode(), code, message);
				}))
				.onSuccess(promise::complete)
				.onFailure(e -> {
					if (e instanceof ToolException) {
						promise.fail(e);
					} else {
						log.error("[DocumentToolsClient] " + op + " : service injoignable", e);
						promise.fail(new ToolException(503, "workspace.transform.unavailable", e.getMessage()));
					}
				});
		return promise.future();
	}

	/** Renseignements sur un PDF : {@code encrypted}, {@code passwordRequired}, {@code pages}, {@code form}. */
	public Future<JsonObject> info(Input pdf) {
		return apply("info", List.of(pdf), new JsonObject()).map(r -> r.content.toJsonObject());
	}
}
