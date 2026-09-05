/*
 * Copyright © "Open Digital Education", 2015
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

 */

package org.entcore.auth.security;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import fr.wseduc.webutils.http.Renders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Politique de robustesse des mots de passe, résolue <b>par thème</b> (skin).
 *
 * <p>Une même plate-forme sert le premier et le second degré sous des hôtes différents
 * (cf. la table {@code skins} de la configuration : hôte -&gt; thème). Les deux publics n'ont
 * pas les mêmes contraintes : un élève d'école élémentaire ne retiendra pas la règle
 * applicable à un compte de collège ou de lycée. La politique est donc :
 *
 * <ul>
 *   <li>{@code passwordRegex} — la règle par défaut, appliquée à tout thème non listé
 *       (second degré, portails académiques, administration) ;</li>
 *   <li>{@code passwordRegexBySkin} — une table {@code thème -> expression régulière} qui
 *       surcharge la règle par défaut pour les thèmes cités (typiquement {@code openent1d}).</li>
 * </ul>
 *
 * <p>La règle retenue vaut aussi bien pour la <b>validation côté serveur</b> (activation de
 * compte et réinitialisation de mot de passe) que pour le <b>libellé rendu au navigateur</b>
 * par {@code GET /auth/context} : les deux passent par cette classe, il n'existe donc pas de
 * cas où l'écran annonce une règle et où le serveur en applique une autre.
 *
 * <p>La classe est un singleton initialisé une fois au démarrage du module {@code auth}. Tant
 * qu'il n'est pas initialisé, {@link #patternFor(HttpServerRequest)} retombe sur une règle
 * permissive : mieux vaut laisser passer une activation que la refuser sur une configuration
 * incomplète.
 */
public class PasswordPolicy {

	private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);

	/** Reprend le défaut historique d'entcore : au moins 8 caractères, sans autre contrainte. */
	public static final String DEFAULT_REGEX = ".{8}.*";

	private static PasswordPolicy instance;

	private final Pattern defaultPattern;
	private final Map<String, Pattern> patternBySkin;
	private final JsonObject skins;

	private PasswordPolicy(JsonObject config, JsonObject skins) {
		this.skins = skins != null ? skins : new JsonObject();
		this.defaultPattern = compile(config.getString("passwordRegex", DEFAULT_REGEX), DEFAULT_REGEX);
		this.patternBySkin = new HashMap<>();
		final JsonObject bySkin = config.getJsonObject("passwordRegexBySkin", new JsonObject());
		for (String skin : bySkin.fieldNames()) {
			final String regex = bySkin.getString(skin);
			if (regex != null && !regex.trim().isEmpty()) {
				patternBySkin.put(skin, compile(regex, null));
			}
		}
		if (!patternBySkin.isEmpty()) {
			log.info("Password policy overridden for skins " + patternBySkin.keySet());
		}
	}

	/**
	 * Compile une expression régulière, en retombant sur {@code fallback} si elle est invalide.
	 * Une regex fautive en configuration ne doit pas empêcher le module de démarrer : elle est
	 * tracée et la règle par défaut s'applique.
	 */
	private static Pattern compile(String regex, String fallback) {
		try {
			return Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			log.error("Invalid password regex in configuration : " + regex, e);
			return Pattern.compile(fallback != null ? fallback : DEFAULT_REGEX);
		}
	}

	public static void init(JsonObject config, JsonObject skins) {
		instance = new PasswordPolicy(config != null ? config : new JsonObject(), skins);
	}

	public static PasswordPolicy getInstance() {
		return instance;
	}

	/** Thème servi à l'hôte de la requête, ou {@code null} si l'hôte n'est pas dans la table. */
	private String skinOf(HttpServerRequest request) {
		if (request == null) {
			return null;
		}
		return skins.getString(Renders.getHost(request));
	}

	/** Règle applicable à la requête courante — jamais {@code null}. */
	public Pattern patternFor(HttpServerRequest request) {
		return patternForSkin(skinOf(request));
	}

	/** Règle applicable à un thème donné — jamais {@code null}. */
	public Pattern patternForSkin(String skin) {
		final Pattern override = skin != null ? patternBySkin.get(skin) : null;
		return override != null ? override : defaultPattern;
	}

	/** Expression régulière applicable à la requête, telle qu'exposée au navigateur. */
	public String regexFor(HttpServerRequest request) {
		return patternFor(request).pattern();
	}

	/**
	 * Vérifie un mot de passe pour la requête courante. Sans politique initialisée, accepte —
	 * refuser une activation faute de configuration serait pire que l'inverse.
	 */
	public static boolean matches(HttpServerRequest request, String password) {
		if (password == null) {
			return false;
		}
		final PasswordPolicy policy = getInstance();
		return policy == null || policy.patternFor(request).matcher(password).matches();
	}
}
