/*
 * Copyright © "Open Digital Education", 2025
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.
 *
 */

package org.entcore.auth;

import io.vertx.core.json.JsonArray;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Les trois nombres proposés lors d'une approbation de connexion.
 *
 * <p>Ce n'est pas un détail d'affichage : c'est ce qui oblige à regarder l'écran de la connexion
 * avant d'approuver. Trois nombres qui se ressemblent, ou un bon nombre qu'on devine, et le
 * contrôle ne vaut plus rien — on retombe sur un bouton « oui » que l'on finit par presser quand
 * les demandes se répètent.</p>
 */
public class DeviceApprovalNumbersTest {

    @Test
    public void alwaysOffersThreeDistinctTwoDigitNumbers() throws Exception {
        for (int i = 0; i < 500; i++) {
            final int expected = 10 + (i % 90);
            final JsonArray numbers = decoysAround(expected);

            assertEquals("trois choix, pas deux", 3, numbers.size());
            final Set<Integer> distinct = new HashSet<>();
            for (Object n : numbers) {
                final int value = (Integer) n;
                assertTrue("un nombre à deux chiffres se lit d'un coup d'œil",
                        value >= 10 && value <= 99);
                distinct.add(value);
            }
            assertEquals("deux nombres identiques rendraient le choix ambigu", 3, distinct.size());
            assertTrue("le bon nombre doit figurer parmi les choix", distinct.contains(expected));
        }
    }

    @Test
    public void keepsDecoysFarEnoughApart() throws Exception {
        // Des nombres voisins (42 et 43) se confondent au premier regard, et c'est justement ce
        // regard qu'on cherche à obtenir.
        for (int i = 0; i < 500; i++) {
            final JsonArray numbers = decoysAround(10 + (i % 90));
            for (int a = 0; a < numbers.size(); a++) {
                for (int b = a + 1; b < numbers.size(); b++) {
                    final int gap = Math.abs(numbers.getInteger(a) - numbers.getInteger(b));
                    assertTrue("deux choix trop proches : " + numbers.encode(), gap >= 10);
                }
            }
        }
    }

    @Test
    public void doesNotAlwaysPutTheRightNumberInTheSamePlace() throws Exception {
        // Une position fixe se repérerait vite, et il suffirait de toujours viser la même case.
        final Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            final int expected = 42;
            final JsonArray numbers = decoysAround(expected);
            positions.add(numbers.getList().indexOf(expected));
        }
        assertEquals("le bon nombre doit pouvoir occuper les trois positions", 3, positions.size());
    }

    /** La méthode testée est privée : c'est une règle interne, pas une interface publique. */
    private static JsonArray decoysAround(final int expected) throws Exception {
        final Method method = Class
                .forName("org.entcore.auth.services.impl.MongoDbDeviceApprovalService")
                .getDeclaredMethod("decoysAround", int.class);
        method.setAccessible(true);
        return (JsonArray) method.invoke(null, expected);
    }

}
