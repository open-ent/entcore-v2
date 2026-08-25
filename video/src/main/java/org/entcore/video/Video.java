/* Copyright © "Open Digital Education", 2026
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

package org.entcore.video;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.entcore.common.controller.ConfController;
import org.entcore.common.events.EventStore;
import org.entcore.common.events.EventStoreFactory;
import org.entcore.common.http.BaseServer;
import org.entcore.common.storage.Storage;
import org.entcore.common.storage.StorageFactory;
import org.entcore.common.storage.impl.MongoDBApplicationStorage;
import org.entcore.video.controllers.VideoController;
import org.entcore.video.services.VideoJobStore;
import org.entcore.video.services.impl.FfmpegVideoEncodingService;

/**
 * DRAFT — verticle bootstrap for the "video" module, mirroring the shape of
 * {@code org.entcore.workspace.Workspace}.
 *
 * IMPORTANT (à vérifier avant tout déploiement) : la {@link Storage} ci-dessous est
 * ouverte sur la collection Mongo {@code "documents"}, la même que celle utilisée par
 * le module workspace (cf. {@code DocumentDao.DOCUMENTS_COLLECTION}). C'est voulu : ça
 * permet à {@link VideoController} d'écrire le fichier encodé directement dans le bucket
 * que WorkspaceController sait lire, sans étape de copie. Ce choix n'a été vérifié que
 * pour un backend GridFS ; avec un backend S3/FileSystem partagé la question ne se pose
 * probablement pas (bucket global) mais ça reste à confirmer avant prod. Voir aussi
 * modules/rack (fr.wseduc.rack.Rack) qui utilise sa propre collection "rack" puis
 * storage.copyFile(...) pour republier dans "documents" — variante plus prudente si
 * l'hypothèse ci-dessus s'avère fausse.
 */
public class Video extends BaseServer {

	@Override
	public void start(final Promise<Void> startPromise) throws Exception {
		final Promise<Void> promise = Promise.promise();
		super.start(promise);
		promise.future()
				.compose(v -> StorageFactory.build(vertx, config,
						new MongoDBApplicationStorage("documents", Video.class.getSimpleName())))
				.compose(this::initVideo)
				.onComplete(startPromise);
	}

	private Future<Void> initVideo(StorageFactory storageFactory) {
		final Storage storage = storageFactory.getStorage();

		// Pas de setDefaultResourceFilter() : tous les @SecuredAction du contrôleur sont
		// de type WORKFLOW/AUTHENTICATED (pas de droit par ressource à arbitrer).

		final EventStore eventStore = EventStoreFactory.getFactory().getEventStore(Video.class.getSimpleName());

		final FfmpegVideoEncodingService encodingService = new FfmpegVideoEncodingService(
				vertx,
				config.getString("ffmpeg-path", "/usr/bin/ffmpeg"),
				config.getLong("encode-timeout-ms", 300000L));

		// TODO(sketch): en cluster multi-nœud, remplacer cette map en mémoire par un
		// SharedData clusterisé (ou une petite table Postgres) : /video/status/:id doit
		// pouvoir être servi par un autre nœud que celui qui a lancé l'encodage.
		final VideoJobStore jobStore = new VideoJobStore();

		final VideoController videoController = new VideoController(
				storage, encodingService, jobStore, eventStore, vertx.eventBus());
		addController(videoController);
		addController(new ConfController());

		return Future.succeededFuture();
	}
}
