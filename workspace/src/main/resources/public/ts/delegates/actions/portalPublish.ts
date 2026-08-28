import { model, idiom as lang, notify, http, template } from "entcore";
import { models } from "../../services";

/**
 * « Publier sur le portail public » : réservé à l'ADML (ou SUPER_ADMIN) de l'établissement
 * du propriétaire du document — le contrôle réel est fait côté serveur
 * (WorkspaceResourcesProvider.authorizeAdmlOfDocumentOwner) ; le ng-if côté client n'est
 * qu'un confort d'affichage, cf. directory/.../account.ts qui utilise le même
 * model.me.functions.ADMIN_LOCAL.
 */
export interface PortalPublishDelegateScope {
    portalPublishUrl: string;
    portalPublishLoading: boolean;
    canPortalPublish(): boolean;
    isPortalPublished(): boolean;
    openPortalPublishView();
    closePortalPublishView();
    portalPublish();
    portalUnpublish();
    //from others
    selectedItems(): models.Element[]
    safeApply()
}

export function ActionPortalPublishDelegate($scope: PortalPublishDelegateScope) {
    $scope.portalPublishUrl = null;
    $scope.portalPublishLoading = false;

    $scope.canPortalPublish = function () {
        const functions: any = model.me && (model.me as any).functions;
        return !!functions && (!!functions["ADMIN_LOCAL"] || !!functions["SUPER_ADMIN"])
            && $scope.selectedItems().length === 1;
    }

    $scope.isPortalPublished = function () {
        const doc: any = $scope.selectedItems()[0];
        return !!(doc && doc.portalPublication);
    }

    $scope.openPortalPublishView = function () {
        const doc: any = $scope.selectedItems()[0];
        $scope.portalPublishUrl = doc && doc.portalPublication ? '/workspace/pub/document/' + doc._id : null;
        template.open('lightbox', 'portalPublish');
    }

    $scope.closePortalPublishView = function () {
        template.close('lightbox');
    }

    $scope.portalPublish = function () {
        const doc: any = $scope.selectedItems()[0];
        if (!doc) {
            return;
        }
        $scope.portalPublishLoading = true;
        http().put('/workspace/document/' + doc._id + '/portal-publish').done((res: any) => {
            doc.portalPublication = { publishedAt: new Date().toISOString() };
            $scope.portalPublishUrl = res && res.url;
            $scope.portalPublishLoading = false;
            $scope.safeApply();
        }).error(() => {
            $scope.portalPublishLoading = false;
            notify.error(lang.translate('workspace.document.publish.error'));
            $scope.safeApply();
        });
    }

    $scope.portalUnpublish = function () {
        const doc: any = $scope.selectedItems()[0];
        if (!doc) {
            return;
        }
        $scope.portalPublishLoading = true;
        http().delete('/workspace/document/' + doc._id + '/portal-publish').done(() => {
            delete doc.portalPublication;
            $scope.portalPublishUrl = null;
            $scope.portalPublishLoading = false;
            $scope.safeApply();
        }).error(() => {
            $scope.portalPublishLoading = false;
            notify.error(lang.translate('workspace.document.publish.error'));
            $scope.safeApply();
        });
    }
}
