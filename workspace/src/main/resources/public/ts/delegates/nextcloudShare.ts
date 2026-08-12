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

    // display existe déjà (initialisé par le contrôleur avant les délégués) ; accès via
    // cast `any` (display n'est volontairement pas typé dans NextcloudShareDelegateScope).
    const disp: any = ($scope as any).display || (($scope as any).display = {});
    disp.ncShare = { mode: 'copy', opened: false, loading: false, path: "", folders: [], submitting: false };

    const nc = () => (($scope as any).display.ncShare);

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
    $scope.browseNextcloudFolder = function (path: string) {
        nc().loading = true;
        nc().path = path || "";
        const userId = model.me.userId;
        const pathParam = path ? `?path=${encodeURIComponent(path)}` : "";
        http().get(`/nextcloud/files/user/${userId}${pathParam}`).done((res: any) => {
            const data = (res && res.data) ? res.data : [];
            nc().folders = data
                .filter((d: any) => d.isFolder)
                .map((d: any) => ({ name: d.displayname || d.name, path: (d.path || '').replace(/\/$/, '') }));
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
