package org.entcore.directory.services.impl;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Liste des favoris de partage : lecture des propriétés du nœud ShareBookmark. */
public class DefaultShareBookmarkServiceTest {

    private static JsonObject byId(JsonArray list, String id) {
        for (Object o : list) {
            if (id.equals(((JsonObject) o).getString("id"))) return (JsonObject) o;
        }
        return null;
    }

    @Test
    public void listeLesFavorisAvecLeurNom() {
        final JsonObject node = new JsonObject()
                .put("_a", new JsonArray().add("Équipe 4e").add("u1").add("u2"))
                .put("_b", new JsonArray().add("Parents").add("g1"));
        final JsonArray list = DefaultShareBookmarkService.bookmarksOf(node, id -> fail("rien à supprimer"));
        assertEquals(2, list.size());
        assertEquals("Équipe 4e", byId(list, "_a").getString("name"));
        assertEquals("Parents", byId(list, "_b").getString("name"));
    }

    @Test
    public void ignoreSansLaSupprimerUneProprieteQuiNestPasUnFavori() {
        final JsonObject node = new JsonObject()
                .put("_a", new JsonArray().add("Équipe").add("u1"))
                .put("id", "3f2b-texte")
                .put("created", 1795854618L);
        final List<String> deleted = new ArrayList<>();
        final JsonArray list = DefaultShareBookmarkService.bookmarksOf(node, deleted::add);
        assertEquals(1, list.size());
        assertEquals("_a", list.getJsonObject(0).getString("id"));
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void relitUneListeRendueEnTexteJson() {
        final JsonObject node = new JsonObject().put("_a", "[\"Équipe\",\"u1\"]");
        final JsonArray list = DefaultShareBookmarkService.bookmarksOf(node, id -> fail("rien à supprimer"));
        assertEquals("Équipe", list.getJsonObject(0).getString("name"));
    }

    @Test
    public void signaleUnFavoriSansMembre() {
        final JsonObject node = new JsonObject().put("_vide", new JsonArray().add("Sans membre"));
        final List<String> deleted = new ArrayList<>();
        assertEquals(0, DefaultShareBookmarkService.bookmarksOf(node, deleted::add).size());
        assertEquals(List.of("_vide"), deleted);
    }

    @Test
    public void noeudAbsent() {
        assertEquals(0, DefaultShareBookmarkService.bookmarksOf(null, id -> fail()).size());
    }
}
