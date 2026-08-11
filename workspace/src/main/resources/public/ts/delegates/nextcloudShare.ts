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
 * NB : à builder + tester dans l'app (UI AngularJS non testable hors navigateur).
 */
export interface NextcloudShareDelegateScope {
    ENABLE_NEXTCLOUD: boolean;
    selectedItems(): models.Element[];
    safeApply();
    ncShare: {
        mode: 'move' | 'copy';
        opened: boolean;
        loading: boolean;
        path: string;           // dossier NextCloud courant dans le sélecteur ("" = racine)
        folders: Array<{ name: string, path: string }>;
        submitting: boolean;
    };
    // condition d'affichage des boutons
    canShareToNextcloud(): boolean;
    // ouvre le sélecteur de dossier NextCloud pour le mode donné
    openNextcloudShare(mode: 'move' | 'copy'): void;
    // navigue dans l'arbre NextCloud (sélecteur)
    browseNextcloudFolder(path: string): void;
    // valide : déplace/copie les documents sélectionnés vers le dossier courant
    confirmNextcloudShare(): void;
    closeNextcloudShare(): void;
}

export function NextcloudShareDelegate($scope: NextcloudShareDelegateScope) {

    $scope.ncShare = { mode: 'copy', opened: false, loading: false, path: "", folders: [], submitting: false };

    // Bouton visible seulement si NextCloud est activé et qu'au moins un DOCUMENT
    // (pas un dossier) est sélectionné.
    $scope.canShareToNextcloud = function (): boolean {
        if (!$scope.ENABLE_NEXTCLOUD) return false;
        const sel = $scope.selectedItems ? $scope.selectedItems() : [];
        return sel && sel.length > 0 && sel.every(e => (e as any).eType !== 'folder');
    };

    $scope.openNextcloudShare = function (mode: 'move' | 'copy') {
        $scope.ncShare.mode = mode;
        $scope.ncShare.opened = true;
        $scope.browseNextcloudFolder("");
    };

    $scope.closeNextcloudShare = function () {
        $scope.ncShare.opened = false;
    };

    // Charge les sous-dossiers NextCloud du chemin donné (sélecteur de destination).
    $scope.browseNextcloudFolder = function (path: string) {
        $scope.ncShare.loading = true;
        $scope.ncShare.path = path || "";
        const userId = model.me.userId;
        const pathParam = path ? `?path=${encodeURIComponent(path)}` : "";
        http().get(`/nextcloud/files/user/${userId}${pathParam}`).done((res: any) => {
            const data = (res && res.data) ? res.data : [];
            $scope.ncShare.folders = data
                .filter((d: any) => d.isFolder)
                .map((d: any) => ({ name: d.displayname || d.name, path: (d.path || '').replace(/\/$/, '') }));
            $scope.ncShare.loading = false;
            $scope.safeApply();
        }).error(() => {
            $scope.ncShare.loading = false;
            notify.error(lang.translate("nextcloud.share.picker.error"));
            $scope.safeApply();
        });
    };

    $scope.confirmNextcloudShare = function () {
        const sel = ($scope.selectedItems ? $scope.selectedItems() : []).filter(e => (e as any).eType !== 'folder');
        if (!sel.length) { $scope.closeNextcloudShare(); return; }
        const ids = sel.map(e => (e as any)._id);
        const userId = model.me.userId;
        const target = $scope.ncShare.path || "";
        const targetParam = target ? `&parentName=${encodeURIComponent(target)}` : "";
        const idsParam = ids.map(id => `id=${encodeURIComponent(id)}`).join('&');
        const verb = $scope.ncShare.mode === 'move' ? 'move' : 'copy';
        $scope.ncShare.submitting = true;
        http().put(`/nextcloud/files/user/${userId}/workspace/${verb}/cloud?${idsParam}${targetParam}`).done(() => {
            $scope.ncShare.submitting = false;
            $scope.ncShare.opened = false;
            notify.info(lang.translate($scope.ncShare.mode === 'move'
                ? "nextcloud.share.move.success" : "nextcloud.share.copy.success"));
            $scope.safeApply();
        }).error(() => {
            $scope.ncShare.submitting = false;
            notify.error(lang.translate("nextcloud.share.error"));
            $scope.safeApply();
        });
    };
}
