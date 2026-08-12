import { model, idiom as lang, notify, http } from 'entcore'
import { models } from "../services";

/**
 * Délégué « Partager avec NextCloud » sur les documents natifs de l'espace doc.
 *
 * Deux actions, guardées par ENABLE_NEXTCLOUD, sur les documents sélectionnés :
 *  - Déplacer vers NextCloud  -> PUT /nextcloud/files/user/:id/workspace/move/cloud
 *  - Partager (copier) vers NextCloud -> PUT /nextcloud/files/user/:id/workspace/copy/cloud
 *
 * Cible = un dossier NextCloud choisi par l'utilisateur (sélecteur maison : entcore
 * FolderPicker ne pique que des dossiers WORKSPACE, pas NextCloud). Le sélecteur
 * charge les dossiers NextCloud via GET /nextcloud/files/user/:id?path=...
 *
 * IMPORTANT : l'état est stocké sous $scope.display.ncShare (et NON $scope.ncShare)
 * car les lightboxes du workspace ne voient que le scope `display.*` (sinon la
 * lightbox reste invisible même si la logique tourne).
 */
// NB : on NE redéclare PAS `display` ici (les autres délégués le déclarent déjà avec
// une forme différente -> conflit TS2430 sur WorkspaceScope). On accède à display.ncShare
// via cast `any` dans le corps du délégué.
export interface NextcloudShareDelegateScope {
    ENABLE_NEXTCLOUD: boolean;
    selectedItems(): models.Element[];
    safeApply();
    canShareToNextcloud(): boolean;
    openNextcloudShare(mode: 'move' | 'copy'): void;
    browseNextcloudFolder(path: string): void;
    confirmNextcloudShare(): void;
    closeNextcloudShare(): void;
}

export function NextcloudShareDelegate($scope: NextcloudShareDelegateScope) {

    // display peut être RÉASSIGNÉ par le contrôleur APRÈS l'init du délégué (navigation, reset…),
    // ce qui effacerait un ncShare posé une seule fois ici -> `Cannot set 'mode' of undefined`.
    // On (re)crée donc ncShare À LA VOLÉE sur le display COURANT à chaque accès.
    const nc = () => {
        const d: any = ($scope as any).display || (($scope as any).display = {});
        if (!d.ncShare) {
            d.ncShare = { mode: 'copy', opened: false, loading: false, path: "", folders: [], submitting: false };
        }
        return d.ncShare;
    };

    // Bouton visible seulement si NextCloud est activé et qu'au moins un DOCUMENT
    // (pas un dossier) est sélectionné.
    $scope.canShareToNextcloud = function (): boolean {
        if (!$scope.ENABLE_NEXTCLOUD) return false;
        const sel = $scope.selectedItems ? $scope.selectedItems() : [];
        return sel && sel.length > 0 && sel.every(e => (e as any).eType !== 'folder');
    };

    $scope.openNextcloudShare = function (mode: 'move' | 'copy') {
        nc().mode = mode;
        nc().opened = true;
        $scope.browseNextcloudFolder("");
    };

    $scope.closeNextcloudShare = function () {
        nc().opened = false;
    };

    // Charge les sous-dossiers NextCloud du chemin donné (sélecteur de destination).
    // ATTENTION aux deux conventions de chemin côté connecteur :
    //  - en ENTRÉE (?path= du listing, parentName du copy/move) le back attend un chemin
    //    RELATIF à la racine de l'utilisateur (ex. "Documents", "" = racine) ;
    //  - en SORTIE (d.path) le back renvoie le href WebDAV COMPLET
    //    (/remote.php/dav/files/<userId>/Documents/). On convertit donc href -> relatif,
    //    sinon le back re-préfixe la base DAV et le fichier part dans le vide (200 mais rien).
    $scope.browseNextcloudFolder = function (path: string) {
        nc().loading = true;
        const rel = (path || "").replace(/^\/+/, "").replace(/\/+$/, "");
        nc().path = rel;
        const userId = model.me.userId;
        // href WebDAV brut -> chemin relatif à la racine de l'utilisateur (tout ce qui suit /<userId>)
        const toRel = (href: string): string => {
            let p = decodeURIComponent(href || "");
            const i = p.indexOf(`/${userId}`);
            if (i >= 0) p = p.substring(i + `/${userId}`.length);
            return p.replace(/^\/+/, "").replace(/\/+$/, "");
        };
        // Dossiers techniques masqués (tambouille NextCloud, sans sens pour un prof/élève) :
        // dossier de synchro d'établissement, dossier de gabarits, dossiers cachés.
        const HIDDEN = /^(Templates|ENT_PARTAGE_UAI_.*|\..*)$/i;
        const pathParam = rel ? `?path=${encodeURIComponent(rel)}` : "";
        http().get(`/nextcloud/files/user/${userId}${pathParam}`).done((res: any) => {
            const data = (res && res.data) ? res.data : [];
            nc().folders = data
                .filter((d: any) => d.isFolder)
                .map((d: any) => ({ name: d.displayname || d.name, path: toRel(d.path) }))
                // on retire l'entrée « self » (le dossier courant lui-même, renvoyé par PROPFIND)
                // et les dossiers techniques.
                .filter((f: any) => f.path && f.path !== rel && !HIDDEN.test(f.name));
            nc().loading = false;
            $scope.safeApply();
        }).error(() => {
            nc().loading = false;
            notify.error(lang.translate("nextcloud.share.picker.error"));
            $scope.safeApply();
        });
    };

    $scope.confirmNextcloudShare = function () {
        const sel = ($scope.selectedItems ? $scope.selectedItems() : []).filter(e => (e as any).eType !== 'folder');
        if (!sel.length) { $scope.closeNextcloudShare(); return; }
        const ids = sel.map(e => (e as any)._id);
        const userId = model.me.userId;
        const target = nc().path || "";
        const targetParam = target ? `&parentName=${encodeURIComponent(target)}` : "";
        const idsParam = ids.map(id => `id=${encodeURIComponent(id)}`).join('&');
        const verb = nc().mode === 'move' ? 'move' : 'copy';
        nc().submitting = true;
        http().put(`/nextcloud/files/user/${userId}/workspace/${verb}/cloud?${idsParam}${targetParam}`).done(() => {
            nc().submitting = false;
            nc().opened = false;
            notify.info(lang.translate(nc().mode === 'move'
                ? "nextcloud.share.move.success" : "nextcloud.share.copy.success"));
            $scope.safeApply();
        }).error(() => {
            nc().submitting = false;
            notify.error(lang.translate("nextcloud.share.error"));
            $scope.safeApply();
        });
    };
}
