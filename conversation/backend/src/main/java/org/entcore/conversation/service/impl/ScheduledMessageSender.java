/*
 * Copyright © Open ENT, 2026 — AGPL v3.
 * Envoi différé : worker périodique qui dépile les messages programmés arrivés à échéance
 * et les délivre en rejouant le chemin d'envoi normal (hors requête HTTP).
 */
package org.entcore.conversation.service.impl;

import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.user.UserInfos;
import org.entcore.conversation.service.ConversationService;
import org.entcore.conversation.util.MessagingHours;

import java.util.ArrayList;
import java.util.List;

/**
 * Déclenché par un {@link fr.wseduc.cron.CronTrigger} (~ chaque minute). Pour chaque message
 * {@code SCHEDULED} dont {@code scheduled_at <= now} :
 * <ul>
 *   <li>revérifie les horaires de messagerie via le {@code sender_context} figé à la programmation
 *       (règle « on retient hors plage » : si non autorisé, on laisse programmé pour le prochain scan) ;</li>
 *   <li>reconstruit un {@link UserInfos} minimal (l'envoi n'utilise que {@code userId}) et résout les
 *       destinataires via {@code findInactives} (peuple {@code allUsers}) ;</li>
 *   <li>rejoue {@link ConversationService#send} (bascule l'état en {@code SENT} + délivre aux destinataires).</li>
 * </ul>
 */
public class ScheduledMessageSender implements Handler<Long> {

	private static final Logger log = LoggerFactory.getLogger(ScheduledMessageSender.class);

	private final ConversationService conversationService;
	private final Neo4jConversationService userService;

	public ScheduledMessageSender(ConversationService conversationService, Neo4jConversationService userService) {
		this.conversationService = conversationService;
		this.userService = userService;
	}

	@Override
	public void handle(Long timerId) {
		final long now = System.currentTimeMillis();
		conversationService.listDueScheduledMessages(now)
			.onSuccess(due -> {
				if (due != null && !due.isEmpty()) {
					log.info("[Scheduled] " + due.size() + " message(s) à envoyer");
				}
				for (Object o : due) {
					if (o instanceof JsonObject) {
						dispatch((JsonObject) o);
					}
				}
			})
			.onFailure(th -> log.error("[Scheduled] échec listing des messages dus : " + th.getMessage()));
	}

	private void dispatch(final JsonObject row) {
		final String id = row.getString("id");
		final String from = row.getString("from");
		final String parentId = row.getString("parent_id");
		final JsonObject ctx = row.getJsonObject("sender_context", new JsonObject());
		final String type = ctx.getString("type");
		final List<String> structures = toStringList(ctx.getJsonArray("structures"));

		// Règle « on retient hors plage » : si l'expéditeur n'est pas autorisé à envoyer maintenant
		// (élève hors créneau), on ne fait rien — le message sera repris au prochain scan.
		if (type != null && !MessagingHours.getInstance().isSendAllowed(type, structures)) {
			log.debug("[Scheduled] message " + id + " retenu (hors plage horaire pour " + type + ")");
			return;
		}

		final UserInfos user = new UserInfos();
		user.setUserId(from);
		user.setType(type);
		user.setUsername(ctx.getString("username"));
		user.setStructures(structures);

		conversationService.get(id, user, new Handler<Either<String, JsonObject>>() {
			@Override
			public void handle(Either<String, JsonObject> getEvent) {
				if (getEvent.isLeft()) {
					log.error("[Scheduled] lecture du message " + id + " échouée : " + getEvent.left().getValue());
					return;
				}
				final JsonObject msg = getEvent.right().getValue();
				final JsonArray attachments = msg.getJsonArray("attachments", new JsonArray());
				long size = 0L;
				for (Object a : attachments) {
					if (a instanceof JsonObject) size += ((JsonObject) a).getLong("size", 0L);
				}
				userService.findInactives(msg, size, new Handler<JsonObject>() {
					@Override
					public void handle(JsonObject userDetails) {
						msg.mergeIn(userDetails);
						conversationService.send(parentId, id, msg, user, new Handler<Either<String, JsonObject>>() {
							@Override
							public void handle(Either<String, JsonObject> sendEvent) {
								if (sendEvent.isRight()) {
									log.info("[Scheduled] message " + id + " envoyé (programmé) par " + from);
								} else {
									log.error("[Scheduled] envoi du message " + id + " échoué : " + sendEvent.left().getValue());
								}
							}
						});
					}
				});
			}
		});
	}

	private static List<String> toStringList(JsonArray array) {
		final List<String> list = new ArrayList<>();
		if (array != null) {
			for (Object o : array) {
				if (o != null) list.add(o.toString());
			}
		}
		return list;
	}
}
