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

import org.entcore.auth.security.UserAgents;
import org.entcore.auth.services.impl.MongoDbDeviceService;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Couvre les deux déductions faites sur la description d'un appareil : le réseau d'origine,
 * qui décide si une connexion est inhabituelle, et le libellé affiché à l'utilisateur.
 */
public class DeviceIdentificationTest {

    @Test
    public void ipPrefixKeepsTheIpv4Network() {
        assertEquals("192.168.1", MongoDbDeviceService.ipPrefix("192.168.1.42"));
        // Deux baux DHCP successifs sur le même réseau ne doivent pas déclencher d'alerte.
        assertEquals(
                MongoDbDeviceService.ipPrefix("192.168.1.42"),
                MongoDbDeviceService.ipPrefix("192.168.1.77"));
    }

    @Test
    public void ipPrefixUnwrapsIpv4MappedAddresses() {
        // Forme produite derrière un ingress : l'IPv4 encapsulée en IPv6.
        assertEquals("172.20.0", MongoDbDeviceService.ipPrefix("::ffff:172.20.0.1"));
    }

    @Test
    public void ipPrefixKeepsOnlyTheClientPartOfXForwardedFor() {
        assertEquals("10.1.2", MongoDbDeviceService.ipPrefix("10.1.2.3, 10.9.9.9, 10.8.8.8"));
    }

    @Test
    public void ipPrefixKeepsFourGroupsOfIpv6() {
        assertEquals("2001:db8:85a3:0", MongoDbDeviceService.ipPrefix("2001:db8:85a3:0:0:8a2e:370:7334"));
    }

    @Test
    public void ipPrefixIsNullWhenTheAddressIsUnusable() {
        assertNull(MongoDbDeviceService.ipPrefix(null));
        assertNull(MongoDbDeviceService.ipPrefix(""));
        assertNull(MongoDbDeviceService.ipPrefix("pas-une-adresse"));
        // Adresse tronquée : mieux vaut aucun préfixe qu'un préfixe faux, qui ferait
        // passer un réseau inconnu pour un réseau déjà vu.
        assertNull(MongoDbDeviceService.ipPrefix("192.168.1"));
    }

    @Test
    public void userAgentIsDescribedAsBrowserAndPlatform() {
        assertEquals("Chrome sur Linux", UserAgents.describe(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "sur"));
        assertEquals("Firefox sur Windows", UserAgents.describe(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0", "sur"));
    }

    @Test
    public void chromiumDerivativesAreNotMistakenForChrome() {
        // Edge et Opera s'annoncent aussi comme Chrome : l'ordre du tableau compte.
        assertEquals("Edge sur Windows", UserAgents.describe(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0", "sur"));
        assertEquals("Opera sur Windows", UserAgents.describe(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/119.0.0.0 Safari/537.36 OPR/105.0.0.0", "sur"));
    }

    @Test
    public void unrecognizableUserAgentYieldsNoLabel() {
        assertNull(UserAgents.describe(null, "sur"));
        assertNull(UserAgents.describe("", "sur"));
        assertNull(UserAgents.describe("curl/8.4.0", "sur"));
    }

}
