/* Copyright © "Open Digital Education", 2025
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

package org.entcore.auth.security;

import static fr.wseduc.webutils.Utils.isEmpty;

/**
 * Traduit un User-Agent en un libellé lisible du type « Chrome sur Windows », pour les
 * notifications de connexion depuis un appareil inconnu.
 *
 * <p>Volontairement sommaire : il ne s'agit pas d'identifier un client mais d'aider une
 * personne à reconnaître, ou non, sa propre connexion. Le User-Agent brut reste disponible
 * à côté du libellé.</p>
 *
 * <p><u>NB :</u> la page « Appareils » du tableau de bord fait la même déduction côté
 * navigateur ({@code frontend/apps/dashboard/src/utils/userAgent.ts}) ; les deux listes
 * doivent rester cohérentes.</p>
 */
public final class UserAgents {

    /** Navigateurs, dans l'ordre : les dérivés de Chromium s'annoncent tous comme Chrome. */
    private static final String[][] BROWSERS = {
        { "Edg/", "Edge" },
        { "OPR/", "Opera" },
        { "Opera", "Opera" },
        { "SamsungBrowser", "Samsung Internet" },
        { "Firefox", "Firefox" },
        { "Chrome", "Chrome" },
        { "Safari", "Safari" },
    };

    private static final String[][] PLATFORMS = {
        { "Android", "Android" },
        { "iPhone", "iPhone" },
        { "iPad", "iPad" },
        { "Windows", "Windows" },
        { "Macintosh", "macOS" },
        { "Mac OS X", "macOS" },
        { "CrOS", "ChromeOS" },
        { "Linux", "Linux" },
    };

    private UserAgents() {
    }

    /**
     * @param separator mot de liaison localisé (« sur », « on »…)
     * @return par exemple « Chrome sur Linux », ou {@code null} si rien n'est reconnaissable
     */
    public static String describe(final String userAgent, final String separator) {
        if (isEmpty(userAgent)) {
            return null;
        }
        final String browser = match(userAgent, BROWSERS);
        final String platform = match(userAgent, PLATFORMS);
        if (browser != null && platform != null) {
            return browser + " " + separator + " " + platform;
        }
        return browser != null ? browser : platform;
    }

    private static String match(final String userAgent, final String[][] table) {
        for (String[] candidate : table) {
            if (userAgent.contains(candidate[0])) {
                return candidate[1];
            }
        }
        return null;
    }

}
