/*
 * Copyright © "PASS Technologie", 2026.
 *
 * This file is part of OPEN ENT NG. OPEN ENT NG is a versatile ENT Project based on the JVM and ENT Core Project.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 */
package org.entcore.conversation.events;

import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.Either.Right;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.search.SearchingEvents;
import org.entcore.common.sql.Sql;

import java.util.List;
import java.util.stream.Collectors;

import static org.entcore.common.sql.SqlResult.validResult;

/**
 * Rend les messages de la messagerie interrogeables depuis le moteur de recherche.
 * <p>
 * Le module conversation ne déclarait aucune {@link SearchingEvents} : le contenu
 * des messages échappait donc à la recherche globale, alors même que la table
 * {@code conversation.messages} porte déjà une colonne {@code text_searchable}
 * (tsvector, index GIN, configuration française avec unaccent) entretenue par un
 * trigger et utilisée par la recherche interne de la messagerie. On réutilise
 * exactement cette colonne : aucune réindexation n'est nécessaire.
 * <p>
 * <b>Droits.</b> La jointure sur {@code conversation.usermessages} borne la
 * recherche à la boîte de l'utilisateur : un message qu'il n'a ni reçu ni envoyé
 * n'a pas de ligne pour lui et reste donc invisible. Les brouillons et la
 * corbeille sont exclus.
 */
public class ConversationSearchingEvents implements SearchingEvents {

    private static final Logger log = LoggerFactory.getLogger(ConversationSearchingEvents.class);

    @Override
    public void searchResource(List<String> appFilters, String userId, JsonArray groupIds, JsonArray searchWords,
                               Integer page, Integer limit, final JsonArray columnsHeader,
                               final String locale, final Handler<Either<String, JsonArray>> handler) {
        if (!appFilters.contains(ConversationSearchingEvents.class.getSimpleName())) {
            handler.handle(new Right<String, JsonArray>(new JsonArray()));
            return;
        }

        // Mots combinés en ET, chacun en préfixe (« mot:* ») : sans cela le moteur
        // ne fait que de la radicalisation et « mathé » ne trouve pas
        // « mathématiques », alors que l'utilisateur saisit naturellement un début
        // de mot. « mathémat » fonctionnait déjà, « mathé » non.
        final String tsQuery = searchWords.stream()
            .map(String::valueOf)
            .filter(w -> !w.trim().isEmpty())
            .map(w -> w.trim() + ":*")
            .collect(Collectors.joining(" & "));
        if (tsQuery.isEmpty()) {
            handler.handle(new Right<String, JsonArray>(new JsonArray()));
            return;
        }

        final String query =
            " SELECT m.id, m.subject, m.body, m.date, m.\"from\", m.\"fromName\"" +
            " FROM conversation.messages AS m" +
            " INNER JOIN conversation.usermessages AS um ON um.message_id = m.id" +
            " WHERE um.user_id = ?" +
            "   AND um.trashed = false" +
            "   AND m.state = 'SENT'" +
            "   AND m.text_searchable @@ to_tsquery(m.language::regconfig, unaccent(?))" +
            " ORDER BY m.date DESC LIMIT ? OFFSET ?";

        final JsonArray values = new JsonArray()
            .add(userId)
            .add(tsQuery)
            .add(limit)
            .add(page * limit);

        Sql.getInstance().prepared(query, values, new Handler<Message<JsonObject>>() {
            @Override
            public void handle(Message<JsonObject> event) {
                final Either<String, JsonArray> ei = validResult(event);
                if (ei.isRight()) {
                    handler.handle(new Right<String, JsonArray>(format(ei.right().getValue(), columnsHeader)));
                } else {
                    log.error("[ConversationSearchingEvents] search failed : " + ei.left().getValue());
                    handler.handle(new Either.Left<String, JsonArray>(ei.left().getValue()));
                }
            }
        });
    }

    /** Colonnes attendues par le moteur : title, description, modified, ownerDisplayName, ownerId, url. */
    private JsonArray format(final JsonArray results, final JsonArray columnsHeader) {
        final List<String> header = columnsHeader.getList();
        final JsonArray formatted = new JsonArray();
        for (int i = 0; i < results.size(); i++) {
            final JsonObject row = results.getJsonObject(i);
            if (row == null) continue;
            formatted.add(new JsonObject()
                .put(header.get(0), row.getString("subject", ""))
                .put(header.get(1), stripHtml(row.getString("body", "")))
                .put(header.get(2), new JsonObject().put("$date", row.getLong("date", 0L)))
                .put(header.get(3), row.getString("fromName", ""))
                .put(header.get(4), row.getString("from", ""))
                .put(header.get(5), "/conversation#/read-mail/" + row.getString("id", "")));
        }
        return formatted;
    }

    /** Le corps est stocké en HTML ; l'IHM du moteur affiche du texte brut. */
    private String stripHtml(final String body) {
        if (body == null) return "";
        return body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
