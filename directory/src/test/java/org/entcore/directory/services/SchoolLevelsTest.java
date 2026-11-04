package org.entcore.directory.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SchoolLevelsTest {

	@Test
	public void deduitLesNiveauxDeLaNatureAaf() {
		assertEquals(Collections.singletonList("maternelle"), SchoolLevels.fromType("ECOLE MATERNELLE"));
		assertEquals(Arrays.asList("maternelle", "elementaire"), SchoolLevels.fromType("ECOLE DE NIVEAU ELEMENTAIRE"));
		assertEquals(Arrays.asList("maternelle", "elementaire"), SchoolLevels.fromType("Ecole"));
		assertEquals(Collections.singletonList("college"), SchoolLevels.fromType("COLLEGE"));
		assertEquals(Collections.singletonList("college"), SchoolLevels.fromType("Collège"));
		assertEquals(Collections.singletonList("college"),
				SchoolLevels.fromType("ETABLISSEMENT REGIONAL D'ENSEIGNT ADAPTE"));
		assertEquals(Collections.singletonList("lycee_pro"), SchoolLevels.fromType("LYCEE PROFESSIONNEL"));
		assertEquals(Arrays.asList("lycee", "lycee_pro"), SchoolLevels.fromType("LYCEE POLYVALENT"));
		assertEquals(Collections.singletonList("lycee"),
				SchoolLevels.fromType("LYCEE ENSEIGNT GENERAL ET TECHNOLOGIQUE"));
		assertEquals(Collections.emptyList(), SchoolLevels.fromType("SERVICE DE L INSPECTION ACADEMIQUE"));
		assertEquals(Collections.emptyList(), SchoolLevels.fromType(null));
	}

	@Test
	public void valideEtOrdonneLesNiveauxChoisis() {
		assertEquals(Arrays.asList("elementaire", "college", "lycee"),
				SchoolLevels.validate(new JsonArray().add("lycee").add("elementaire").add("college").add("lycee")));
		assertNull(SchoolLevels.validate(new JsonArray().add("college").add("cp")));
		assertNull(SchoolLevels.validate(new JsonArray().add(3)));
	}

	@Test
	public void traduitEnDegres() {
		assertEquals(Collections.singletonList(1), SchoolLevels.degrees(Arrays.asList("maternelle", "elementaire")));
		assertEquals(Arrays.asList(1, 2), SchoolLevels.degrees(Arrays.asList("elementaire", "college")));
		assertEquals(Collections.singletonList(2), SchoolLevels.degrees(Collections.singletonList("lycee_pro")));
	}

	@Test
	public void valideLaCorrespondanceNiveauTheme() {
		assertEquals(new JsonObject().put("primaire", "openent1d").put("college", "openent3"),
				SchoolLevels.validateLevelThemes(new JsonObject().put("primaire", "openent1d").put("college", "openent3").put("lycee", "")));
		assertNull(SchoolLevels.validateLevelThemes(new JsonObject().put("maternelle", "openent1d")));
		assertNull(SchoolLevels.validateLevelThemes(new JsonObject().put("primaire", "../etc")));
	}
}
