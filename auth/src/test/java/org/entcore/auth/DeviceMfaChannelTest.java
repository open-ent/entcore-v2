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
import io.vertx.core.json.JsonObject;
import org.entcore.auth.services.impl.DefaultMfaService;
import org.entcore.common.utils.Mfa;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Choix du canal de second facteur, et conditions dans lesquelles il est exigible.
 *
 * <p>L'enjeu tient en une phrase : un compte sans adresse e-mail et sans numéro — un élève de
 * primaire, typiquement — n'avait jusqu'ici aucun moyen d'apporter un second facteur. Le lui
 * imposer revenait donc à l'enfermer dehors. L'appareil enrôlé change cela, et ces tests
 * vérifient que le changement va bien dans ce sens, et dans celui-là seulement.</p>
 */
public class DeviceMfaChannelTest {

    @Before
    public void configurePlatform() {
        // Les quatre facteurs ouverts sur la plateforme : les tests font varier ce dont le
        // compte dispose, pas ce que la plateforme autorise.
        //
        // Reposé avant CHAQUE test, et non une fois pour la classe : la configuration MFA est un
        // singleton de processus, que les autres tests de la suite reconfigurent à leur guise.
        Mfa.Factory.getFactory().init(null, new JsonObject().put("mfaConfig",
                new JsonObject().put("types", new JsonArray()
                        .add(Mfa.TYPE_EMAIL).add(Mfa.TYPE_SMS)
                        .add(Mfa.TYPE_TOTP).add(Mfa.TYPE_DEVICE))));
    }

    // --- Choix du canal ---------------------------------------------------------------

    @Test
    public void prefersTheDeviceOverSmsWhenNothingIsChosen() {
        // L'appareil ne coûte rien, n'exige aucun numéro, et n'est pas interceptable par un
        // détournement de carte SIM : il passe devant le SMS.
        assertEquals(Mfa.TYPE_DEVICE,
                DefaultMfaService.resolveChannel(null, false, true, true, true));
    }

    @Test
    public void keepsTheChannelTheUserChose() {
        // Le choix explicite de la personne l'emporte sur l'ordre par défaut.
        assertEquals(Mfa.TYPE_EMAIL,
                DefaultMfaService.resolveChannel(Mfa.TYPE_EMAIL, true, true, true, true));
        assertEquals(Mfa.TYPE_TOTP,
                DefaultMfaService.resolveChannel(Mfa.TYPE_TOTP, true, true, true, true));
        assertEquals(Mfa.TYPE_DEVICE,
                DefaultMfaService.resolveChannel(Mfa.TYPE_DEVICE, true, true, true, true));
    }

    @Test
    public void ignoresAChosenDeviceWhenNoneIsEnrolled() {
        // Mode choisi mais inutilisable : on retombe sur ce dont le compte dispose réellement,
        // plutôt que de présenter un écran sans issue.
        assertEquals(Mfa.TYPE_TOTP,
                DefaultMfaService.resolveChannel(Mfa.TYPE_DEVICE, true, true, false, false));
    }

    @Test
    public void givesAnAccountWithNothingButADeviceAUsableChannel() {
        // Ni adresse, ni numéro, ni application : avant l'appareil, ce compte n'avait aucun
        // second facteur praticable.
        assertEquals(Mfa.TYPE_DEVICE,
                DefaultMfaService.resolveChannel(null, false, false, false, true));
    }

    @Test
    public void keepsTheLegacyOrderWhenNoDeviceIsEnrolled() {
        // Les comptes existants ne doivent voir aucun changement.
        assertEquals(Mfa.TYPE_TOTP,
                DefaultMfaService.resolveChannel(null, true, true, true, false));
        assertEquals(Mfa.TYPE_SMS,
                DefaultMfaService.resolveChannel(null, false, true, true, false));
        assertEquals(Mfa.TYPE_EMAIL,
                DefaultMfaService.resolveChannel(null, false, true, false, false));
    }

    // --- Exigence à la connexion ------------------------------------------------------

    @Test
    public void doesNotRequireMfaWhenTheAccountHasNoFactorAtAll() {
        // La règle qui évite d'enfermer dehors : sans aucun moyen d'apporter une preuve, le
        // second facteur n'est pas exigé.
        assertFalse(Mfa.isRequiredAtLogin(session(false, false, false, false)));
    }

    @Test
    public void requiresMfaOnceADeviceIsEnrolled() {
        assertTrue(Mfa.isRequiredAtLogin(session(true, true, false, false)));
    }

    @Test
    public void neverRequiresMfaWhenTheAccountIsNotConcerned() {
        // mfaAtLogin faux : ni le compte ni son établissement ne l'imposent.
        assertFalse(Mfa.isRequiredAtLogin(session(false, true, true, true)));
        assertFalse(Mfa.isRequiredAtLogin(null));
    }

    private static JsonObject session(final boolean mfaAtLogin, final boolean hasDevice,
                                      final boolean hasTotp, final boolean hasEmail) {
        return new JsonObject()
                .put("mfaAtLogin", mfaAtLogin)
                .put("hasDevice", hasDevice)
                .put("hasTotp", hasTotp)
                .put("email", hasEmail ? "eleve@example.org" : null);
    }

}
