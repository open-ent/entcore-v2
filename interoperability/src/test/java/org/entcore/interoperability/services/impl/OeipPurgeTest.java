package org.entcore.interoperability.services.impl;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ce que la purge détruit, et surtout ce qu'elle ne doit pas détruire.
 *
 * Un paquet OEIP transporte des personnes identifiées, souvent mineures, et annonce lui-même une
 * durée de conservation. Ne pas l'appliquer laisse ces exports s'accumuler indéfiniment sur le
 * disque de la plateforme. Mais une purge trop zélée est pire : elle emporterait un export en
 * cours de préparation.
 */
public class OeipPurgeTest {

    private static final long TTL = 48L * 3600_000L;
    private static final long MAINTENANT = 1_800_000_000_000L;

    private Path workDir;

    @Before
    public void setUp() throws Exception {
        workDir = Paths.get("target", "purge-test", String.valueOf(System.nanoTime()));
        Files.createDirectories(workDir);
    }

    private Path repertoire(String nom, long ageHeures) throws Exception {
        Path p = workDir.resolve(nom);
        Files.createDirectories(p);
        assertTrue(p.toFile().setLastModified(MAINTENANT - ageHeures * 3600_000L));
        return p;
    }

    private static Set<String> travaux(String... ids) {
        return new LinkedHashSet<String>(Arrays.asList(ids));
    }

    private List<Path> selection(Set<String> connus) {
        return OeipPurge.orphelins(workDir, connus, MAINTENANT, TTL);
    }

    // ------------------------------------------------------------------ ce qui doit partir

    @Test
    public void unRepertoireQueAucunTravailNeReclameEstDetruit() throws Exception {
        // Le cas que l'on manque quand la purge se branche sur la seule base : le travail a été
        // effacé, ou la plateforme s'est arrêtée en plein export, et le répertoire subsiste.
        Path orphelin = repertoire("job-abandonne", 72);
        assertEquals(Collections.singletonList(orphelin), selection(travaux()));
    }

    @Test
    public void lesExportsDArchiveIntermediairesSontJugesSurLeurAge() throws Exception {
        // Ils ne portent aucun identifiant de travail : leur nom ne dit rien, seul l'âge parle.
        Path archives = workDir.resolve(OeipPurge.ARCHIVES);
        Files.createDirectories(archives);
        Path vieux = archives.resolve("1789242093258_ec847027");
        Path recent = archives.resolve("1789247115852_ec847027");
        Files.createDirectories(vieux);
        Files.createDirectories(recent);
        vieux.toFile().setLastModified(MAINTENANT - 72L * 3600_000L);
        recent.toFile().setLastModified(MAINTENANT - 3600_000L);

        List<Path> choisis = selection(travaux());
        assertTrue(choisis.contains(vieux));
        assertFalse(choisis.contains(recent));
        // Le répertoire « archives » lui-même n'est jamais supprimé : il est réutilisé.
        assertFalse(choisis.contains(archives));
    }

    // ------------------------------------------------------------------ ce qui doit rester

    @Test
    public void unTravailConnuNEstPasTraiteCommeOrphelin() throws Exception {
        // Son sort dépend de sa date d'expiration, décidée en base — pas de l'âge du répertoire.
        repertoire("job-vivant", 72);
        assertTrue(selection(travaux("job-vivant")).isEmpty());
    }

    @Test
    public void unRepertoireFraichementCreeNEstJamaisUnOrphelin() throws Exception {
        // Un export en cours dont le travail n'est pas encore enregistré : le détruire
        // interromprait une demande légitime.
        repertoire("job-tout-neuf", 0);
        assertTrue(selection(travaux()).isEmpty());
    }

    @Test
    public void unFichierIsoleNEstPasConfonduAvecUnRepertoireDeTravail() throws Exception {
        Files.write(workDir.resolve("trace.log"), "x".getBytes());
        workDir.resolve("trace.log").toFile().setLastModified(MAINTENANT - 72L * 3600_000L);
        assertTrue(selection(travaux()).isEmpty());
    }

    @Test
    public void unRepertoireDeTravailAbsentNeFaitPasEchouerLaSelection() {
        assertTrue(OeipPurge.orphelins(Paths.get("target", "n-existe-pas-" + System.nanoTime()),
                travaux(), MAINTENANT, TTL).isEmpty());
    }
}
