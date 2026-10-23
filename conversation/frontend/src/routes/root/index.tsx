import {
  AppHeader,
  Breadcrumb,
  Layout,
  LoadingScreen,
  useBreakpoint,
  useEdificeClient,
} from '@open-ent/react';
import type { IWebApp } from '@open-ent/client';
import { QueryClient } from '@tanstack/react-query';
import { useLoaderData, useLocation, useParams } from 'react-router-dom';
import { VacationBanner } from '~/components';
import { Config, existingActions } from '~/config';
import {
  CreateFolderModal,
  DesktopMenu,
  MobileMenu,
  MoveMessageToFolderModal,
  RenameFolderModal,
  SignatureModal,
  TrashFolderModal,
  VacationModal,
} from '~/features';
import { AppActionHeader } from '~/features/app/Action/AppActionHeader';
import {
  actionsQueryOptions,
  configQueryOptions,
  folderQueryOptions,
} from '~/services/queries';
import { useActionsStore } from '~/store/actions';
import { ScrollableOutlet } from './components/ScrollableOutlet';
import './index.css';

/**
 * Application affichée quand la messagerie n'est pas dans `/applications-list`. Cette liste vient
 * des rôles portés par les groupes de l'utilisateur ; le super-admin, rattaché à aucun groupe de
 * profil, ne l'y trouve pas alors que le serveur lui ouvre la messagerie. Sans repli, l'écran
 * restait indéfiniment sur le chargement.
 */
const FALLBACK_APP: IWebApp = {
  name: 'Messagerie',
  displayName: 'conversation',
  address: '/conversation/inbox',
  prefix: '/conversation',
  icon: 'conversation-large',
  display: true,
  isExternal: false,
  scope: [''],
};

// Typing for the root route loader.
export interface RootLoaderData {
  actions?: Record<string, boolean>;
  config?: Config;
}

export function loader(queryClient: QueryClient) {
  return async () => {
    try {
      const [actions, config] = await Promise.all([
        queryClient.ensureQueryData(actionsQueryOptions(existingActions)),
        queryClient.ensureQueryData(configQueryOptions.getGlobalConfig()),
      ]);

      // Store those constant values.
      const setWorkflows = useActionsStore.getState().setWorkflows;
      const setConfig = useActionsStore.getState().setConfig;
      if (actions) setWorkflows(actions);
      if (config) setConfig(config);

      // Ensure folders tree loads
      queryClient.ensureQueryData(
        folderQueryOptions.getFoldersTree(config.maxDepth),
      );
      return {
        actions,
        config,
      } as RootLoaderData;
    } catch {
      return {} as RootLoaderData;
    }
  };
}

export function Component() {
  const { init, currentApp: listedApp } = useEdificeClient();
  const currentApp = listedApp ?? FALLBACK_APP;

  const { actions, config } = useLoaderData() as RootLoaderData;

  const { lg } = useBreakpoint();

  const params = useParams();
  const location = useLocation();
  const isNewDraft = location.pathname === '/draft/create';
  const showMobileMenu = !(isNewDraft || params.messageId);

  const openedModal = useActionsStore.use.openedModal();

  if (!init) return <LoadingScreen position={false} />;

  if (!actions || !config) {
    throw 'Unexpected error';
  }

  return (
    <div className="d-print-block d-flex flex-column vh-100 flex-grow-1">
      <Layout>
        <div className="d-print-none">
          <AppHeader render={AppActionHeader}>
            <Breadcrumb app={currentApp} />
          </AppHeader>

          {/* Rappel permanent tant que les réponses automatiques partent. */}
          <VacationBanner />
        </div>

        {!lg && (
          <div className="d-flex flex-column mx-n16 overflow-hidden ">
            {showMobileMenu && (
              <div className="d-print-none d-block px-0 py-12 border-bottom bg-white px-16">
                <MobileMenu />
              </div>
            )}

            <ScrollableOutlet />
          </div>
        )}

        {lg && (
          <div className="d-flex overflow-x-hidden flex-grow-1 me-n16 ">
            <div className="d-print-none desktop-menu flex-column p-16 ps-0 gap-16 border-end bg-white overflow-y-auto overflow-x-hidden">
              <DesktopMenu />
            </div>

            <ScrollableOutlet />
          </div>
        )}

        {(openedModal === 'create' || openedModal === 'create-then-move') && (
          <CreateFolderModal />
        )}
        {openedModal === 'rename' && <RenameFolderModal />}
        {openedModal === 'trash' && <TrashFolderModal />}
        {openedModal === 'move-message' && <MoveMessageToFolderModal />}
        {openedModal === 'signature' && <SignatureModal />}
        {openedModal === 'vacation' && <VacationModal />}
      </Layout>
    </div>
  );
}
