/*
 * Copyright © "Open Digital Education", 2014
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

package org.entcore.directory.services;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Reads the per-structure branding (logo, header, stamp, signature, brand colours) carried by the
 * {@code Structure} node — the {@code s.branding*} properties written by
 * {@code PUT /structure/:id/branding}.
 * <p>
 * These properties have existed for a while but, until now, only WorkflowHub read them (for
 * school certificates and reports). The portal, the mass-mailing PDFs and the notification mails
 * all kept serving {@code /assets/themes/&lt;skin&gt;/img/}, so an établissement that had carefully
 * uploaded its logo and its stamp saw them nowhere but on its certificates. This service is the
 * single place that resolves them, so those callers stop each rediscovering the same Cypher.
 * <p>
 * A theme is per <em>host</em>; branding is per <em>structure</em>. They are layered, not
 * exclusive: whatever the structure does not define falls back to the theme, which is why every
 * field here may legitimately be absent.
 */
public interface StructureBrandingService {

	/** Property prefix on the Structure node, e.g. {@code brandingLogo}. */
	String PROPERTY_PREFIX = "branding";

	/**
	 * Branding of one structure, plus the identity fields the stamp falls back on
	 * ({@code name}, {@code type}, {@code city}). Never fails on an unknown structure: it
	 * resolves to an empty object, because a missing branding is a normal state and must not
	 * turn a mass-mailing into an error.
	 */
	Future<JsonObject> getForStructure(String structureId);

	/**
	 * Branding that applies to a user, resolved from the structures they belong to.
	 * <p>
	 * A user can belong to several structures (a teacher shared between two collèges, an ADML
	 * over a whole département). There is no "main structure" in the model, so the choice is
	 * explicit and stable: the first structure <em>that actually defines a branding</em>, ordered
	 * by name; failing that, the first by name. Ordering by name rather than by node order matters
	 * — otherwise the same user could get a different letterhead from one run to the next.
	 */
	Future<JsonObject> getForUser(String userId);
}
