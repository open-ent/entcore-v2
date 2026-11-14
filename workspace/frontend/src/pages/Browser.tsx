import {
  AppHeader,
  Button,
  Dropdown,
  EmptyScreen,
  LoadingScreen,
  SearchBar,
  SegmentedControl,
  Select,
  ShareModal,
  useEdificeClient,
} from "@open-ent/react";
import { useQueryClient } from "@tanstack/react-query";
import {
  IconAdd,
  IconApplications,
  IconDownload,
  IconFolderAdd,
  IconLandscape,
  IconTextPage,
  IconUpload,
  IconViewList,
} from "@open-ent/react/icons";
import { DragEvent, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Link,
  Navigate,
  useLocation,
  useNavigate,
  useParams,
} from "react-router-dom";
import { ActionBar } from "../components/ActionBar";
import { CommentsModal } from "../components/CommentsModal";
import { ConfirmModal } from "../components/ConfirmModal";
import { LoolCreateModal } from "../components/LoolCreateModal";
import { NextcloudNav } from "../components/NextcloudNav";
import { NextcloudPicker } from "../components/NextcloudPicker";
import { ElementList } from "../components/ElementList";
import { TransferDialog } from "../components/TransferDialog";
import { PublishModal } from "../components/PublishModal";
import { RenameModal } from "../components/RenameModal";
import { ShareWarning } from "../components/ShareWarning";
import { VersionsModal } from "../components/VersionsModal";
import { TransformModal } from "../components/TransformModal";
import { SignatureCheckModal } from "../components/SignatureCheckModal";
import { TRANSFORM_IDS, TransformId } from "../lib/transform";
import { NewFolderModal } from "../components/NewFolderModal";
import { UploadPanel } from "../components/UploadPanel";
import { ZipDialog } from "../components/ZipDialog";
import { LoadMore, PAGE_SIZE } from "../components/LoadMore";
import { QuotaAlert } from "../components/QuotaAlert";
import { QuotaGauge } from "../components/QuotaGauge";
import { TypeFilters } from "../components/TypeFilters";
import { ViewerModal } from "../components/ViewerModal";
import { TreeNav } from "../components/TreeNav";
import { ActionId, availableActions, downloadUrl } from "../lib/actions";
import { breadcrumb, formatSize, isTreeName, sortElements, SortKey } from "../lib/elements";
import { emptyKeys } from "../lib/empty";
import { Carousel } from "../components/Carousel";
import { nextSort, SORT_FIELD, SORT_OPTIONS, sortKeyOf, sortOf, sortOptionValue, viewOf } from "../lib/preferences";
import { applyTypeFilter, typeFilterCounts, TypeFilterKey } from "../lib/typeFilter";
import { isTypingTarget, rangeIds, shortcutOf } from "../lib/selection";
import { readFlags } from "../lib/flags";
import {
  canOpenInGeogebra,
  canOpenInLool,
  canOpenInScratch,
  geogebraUrl,
  LOOL_CREATE,
  LOOL_OPEN,
  loolUrl,
  scratchUrl, ADD_DOCUMENT, ADD_FOLDER } from "../lib/integrations";
import { NEXTCLOUD_VIEW } from "../lib/nextcloud";
import { rightsOn } from "../lib/rights";
import {
  cloudDriveAvailable,
  downloadFromDrive,
  openPicker,
  requestAccessToken,
  uploadToDrive,
} from "../lib/cloudDrive";
import {
  canDropOn,
  DRAG_TYPE,
  draggedElements,
  encodeDrag,
  isInternalDrag,
} from "../lib/dnd";
import { clientFilter, locationOf, searchQuery } from "../lib/search";
import { isZip, planUpload, remainingLabel } from "../lib/upload";
import { TREE_LABEL_KEY, ViewMode, WorkspaceElement } from "../lib/types";
import { useUploads } from "../services/useUploads";
import { nextcloudService } from "../services/nextcloud.service";
import {
  elementActions,
  workspaceService,
} from "../services/workspace.service";
import {
  useAllFiles,
  useContent,
  useFolders,
  useFolderStore,
  useLoolContext,
  useNextcloudList,
  useNextcloudQuota,
  usePublishedIds,
  useQuota,
  useSearch,
  useWorkspacePreference,
} from "../services/queries";

/** Navigation dans l'espace documentaire : arbres, dossiers, fil d'Ariane, liste en icônes ou en tableau, jauge d'espace. */
export const Browser = () => {
  const { t } = useTranslation("workspace");
  const { tree, folderId } = useParams();
  const location = useLocation();
  const [view, setView] = useState<ViewMode>("icons");
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({
    key: "name",
    desc: false,
  });

  // Affichage et tri mémorisés dans la préférence « workspace » de l'usager (la même que celle de l'ancienne interface)
  const preference = useWorkspacePreference();
  const preferenceApplied = useRef(false);
  useEffect(() => {
    if (!preference.data || preferenceApplied.current) return;
    preferenceApplied.current = true;
    setView(viewOf(preference.data));
    const key = sortKeyOf(preference.data.sortField);
    if (key) setSort({ key, desc: preference.data.sortDesc === true });
  }, [preference.data]);
  // Choix de vue en icônes seules : le libellé reste dans la page pour les lecteurs d'écran (masqué par
  // `ws-view-controls`) et s'affiche en infobulle (`title`). Le contrôle du socle ne déclare que `label` et `value`
  // mais transmet l'option entière à Ant Design, qui affiche `icon` et `title`.
  const viewOptions = (
    [
      ["icons", t("workspace.react.view.icons"), <IconApplications width={20} height={20} />],
      ["list", t("workspace.react.view.list"), <IconViewList width={20} height={20} />],
      ["carousel", t("workspace.react.view.carousel"), <IconLandscape width={20} height={20} />],
    ] as const
  ).map(([value, label, icon]) => ({ value, label, title: label, icon }));
  const changeView = (v: ViewMode) => {
    setView(v);
    if (v !== "carousel") void workspaceService.savePreference({ view: v });
  };
  const changeSort = (key: SortKey, toggle = true, desc?: boolean) => {
    const next = toggle ? nextSort(sort, key) : { key, desc: desc ?? false };
    setSort(next);
    void workspaceService.savePreference({
      sortField: SORT_FIELD[next.key],
      sortDesc: next.desc,
    });
  };

  const validTree = isTreeName(tree) ? tree : "owner";
  const content = useContent(validTree, folderId);
  // Filtre par famille (puces au-dessus de la liste) et mode « fichiers les plus lourds » du bandeau de quota.
  const [typeFilter, setTypeFilter] = useState<TypeFilterKey>("all");
  const [heaviest, setHeaviest] = useState(false);
  const allFiles = useAllFiles(validTree, heaviest);
  // Document ouvert dans la visionneuse, avec les documents du même emplacement pour « précédent » / « suivant ».
  const [viewing, setViewing] = useState<{ id: string; siblings: string[] } | null>(null);
  const folders = useFolders();
  const folderStore = useFolderStore();
  // Mode « lazy » : sous-dossiers chargés au dépliage (arbre, sélecteurs) et chaîne des parents remontée pour le fil d'Ariane
  const lazy = folderStore.lazy
    ? { isLoaded: folderStore.isLoaded, onExpand: folderStore.loadChildren }
    : undefined;
  useEffect(() => {
    if (folderId) void folderStore.ensureFolder(folderId);
  }, [folderId, folderStore.ensureFolder]);
  const publicRoot = useContent("public");
  const { user } = useEdificeClient();
  const quota = useQuota(user?.userId);

  // Création et import : dans « Mes documents » et ses dossiers (le partage au dépôt arrive avec le lot W4).
  const groupIds =
    (user as { groupsIds?: string[] } | undefined)?.groupsIds ?? [];
  const functions = ((
    user as { functions?: Record<string, unknown> } | undefined
  )?.functions ?? {}) as Record<string, unknown>;
  const canPublishToPortal = !!(
    functions["ADMIN_LOCAL"] || functions["SUPER_ADMIN"]
  );
  const flags = useMemo(() => readFlags(), []);
  const workflows = new Set(
    (
      (user as { authorizedActions?: Array<{ name: string }> } | undefined)
        ?.authorizedActions ?? []
    ).map((a) => a.name),
  );
  const canOpenOnline = flags.enableLool && workflows.has(LOOL_OPEN);
  // droits de création de la plateforme : un profil en lecture seule (le parent) ne voit ni import ni « Créer un dossier »
  const mayAddDocument = workflows.has(ADD_DOCUMENT);
  const mayAddFolder = workflows.has(ADD_FOLDER);
  const driveAvailable = cloudDriveAvailable(flags);
  const ncAvailable = flags.enableNextcloud && workflows.has(NEXTCLOUD_VIEW);
  const canCreateOnline = flags.enableLool && workflows.has(LOOL_CREATE);
  const lool = useLoolContext(canOpenOnline);
  const ncRoot = useNextcloudList(user?.userId, "", false, ncAvailable);
  const ncQuota = useNextcloudQuota(user?.userId, ncAvailable);
  // Établissements dont l'usager est administrateur local (portée de sa fonction), puis ceux auxquels il est rattaché.
  const adminScope = (
    (functions["ADMIN_LOCAL"] as { scope?: string[] } | undefined)?.scope ?? []
  ).concat(
    (functions["SUPER_ADMIN"] as { scope?: string[] } | undefined)?.scope ?? [],
  );
  const structureIds = [
    ...new Set([
      ...adminScope,
      ...((user as { structures?: string[] } | undefined)?.structures ?? []),
    ]),
  ];
  const published = usePublishedIds(structureIds, canPublishToPortal);
  const currentFolder = (folders.data ?? []).find((f) => f._id === folderId);
  // « Mes documents » et ses dossiers ; dans « Documents partagés », seulement un dossier où l'on a le droit de contribuer.
  const canWrite =
    validTree === "owner" ||
    (validTree === "shared" &&
      !!currentFolder &&
      rightsOn(currentFolder, user?.userId, groupIds).contrib);
  const uploads = useUploads(folderId);
  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [pendingZip, setPendingZip] = useState<File | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  // Recherche : critère saisi, recherche active (dans le dossier courant ou dans tout l'espace)
  const navigate = useNavigate();
  const [criteria, setCriteria] = useState("");
  const [query, setQuery] = useState<{
    criteria: string;
    everywhere: boolean;
  } | null>(null);
  useEffect(() => {
    setQuery(null);
    setCriteria("");
    setTypeFilter("all");
    setHeaviest(false);
  }, [tree, folderId]);
  const searchQs = query
    ? searchQuery({ ...query, tree: validTree, folderId })
    : undefined;
  const search = useSearch(flags.disableFullTextSearch ? undefined : searchQs);
  // Sans index texte (réglage serveur) ou si le serveur le refuse : filtre sur les noms des éléments déjà chargés.
  const searchDegraded =
    !!query && (flags.disableFullTextSearch || search.isError);
  const baseElements = heaviest
    ? (allFiles.data ?? [])
    : !query
      ? (content.data ?? [])
      : searchDegraded
        ? clientFilter(content.data ?? [], query.criteria)
        : (search.data ?? []);
  // Les puces comptent ce que contient l'emplacement, pas ce qu'il en reste une fois filtré.
  const typeCounts = useMemo(() => typeFilterCounts(baseElements), [baseElements]);
  const elements = useMemo(
    () => sortElements(applyTypeFilter(baseElements, typeFilter), sort.key, sort.desc),
    [baseElements, sort, typeFilter],
  );
  // Une famille disparue (après un déplacement, une suppression) ne doit pas laisser la liste vide sans explication.
  useEffect(() => {
    if (typeFilter !== "all" && !typeCounts.some((c) => c.key === typeFilter)) setTypeFilter("all");
  }, [typeCounts, typeFilter]);

  // Sélection et actions de gestion
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [picker, setPicker] = useState<"copy" | "move" | null>(null);
  const [renaming, setRenaming] = useState<WorkspaceElement | null>(null);
  const [renameError, setRenameError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<
    "delete" | "empty" | "trashShared" | null
  >(null);
  const [warnShare, setWarnShare] = useState<WorkspaceElement | null>(null);
  const [sharing, setSharing] = useState<WorkspaceElement | null>(null);
  // Éléments partagés ensemble : la fenêtre règle le premier, ses droits sont ensuite recopiés sur les autres
  const [shareSet, setShareSet] = useState<WorkspaceElement[]>([]);
  const [copyingForShare, setCopyingForShare] = useState(false);
  const [publishing, setPublishing] = useState<WorkspaceElement | null>(null);
  const [versions, setVersions] = useState<WorkspaceElement | null>(null);
  // transformation en cours (menu « Transformer ») et éléments concernés, figés à l'ouverture
  const [checkingSignature, setCheckingSignature] = useState<WorkspaceElement | null>(null);
  const [transforming, setTransforming] = useState<{ id: TransformId; items: WorkspaceElement[] } | null>(null);
  const [commenting, setCommenting] = useState<WorkspaceElement | null>(null);
  const [loolCreate, setLoolCreate] = useState(false);
  const dragged = useRef<WorkspaceElement[]>([]);
  // Un glisser interne est en cours : la liste cesse de s'allonger toute seule tant qu'il dure.
  const [moving, setMoving] = useState(false);
  useEffect(() => {
    const end = () => setMoving(false);
    document.addEventListener("dragend", end);
    document.addEventListener("drop", end);
    return () => {
      document.removeEventListener("dragend", end);
      document.removeEventListener("drop", end);
    };
  }, []);
  const [ncPicker, setNcPicker] = useState<"copy" | "move" | null>(null);
  useEffect(() => setSelected(new Set()), [tree, folderId]);
  // Liste affichée par tranches de 50 : on repart du début à chaque changement d'emplacement, de tri ou de recherche
  const [shown, setShown] = useState(PAGE_SIZE);
  useEffect(() => setShown(PAGE_SIZE), [tree, folderId, sort, query, typeFilter, heaviest]);
  const visible = useMemo(() => elements.slice(0, shown), [elements, shown]);

  const selection = useMemo(
    () => elements.filter((e) => selected.has(e._id)),
    [elements, selected],
  );
  /** Poids total de la sélection : seuls les fichiers en ont un, un dossier n'est pas pesé. */
  const selectionSize = useMemo(
    () => selection.reduce((total, e) => total + (e.eType === "file" ? (e.metadata?.size ?? 0) : 0), 0),
    [selection],
  );
  /** Requête qui alimente la liste : le listing à plat en mode « fichiers les plus lourds », sinon le contenu du dossier. */
  const listed = heaviest ? allFiles : content;
  const sortOptions = useMemo(() => SORT_OPTIONS.map((o) => ({ value: o.value, label: t(o.labelKey) })), [t]);
  const currentSortOption = useMemo(
    () => sortOptions.find((o) => o.value === sortOptionValue(sort)) ?? sortOptions[0],
    [sortOptions, sort],
  );
  const empty = emptyKeys(validTree, !folderId, !!query);
  /** Bandeau de quota : tous les fichiers de l'espace, du plus lourd au plus léger, à la racine de l'emplacement. */
  const showHeaviest = () => {
    setQuery(null);
    setCriteria("");
    setTypeFilter("all");
    setHeaviest(true);
    setSort({ key: "size", desc: true });
    if (validTree !== "owner" || folderId) navigate("/owner");
  };
  const actions = availableActions(validTree, selection, {
    canManage:
      selection.length === 1 &&
      rightsOn(selection[0], user?.userId, groupIds).manager,
    managerOnAll: selection.every(
      (e) => rightsOn(e, user?.userId, groupIds).manager,
    ),
    contribOnAll: selection.every(
      (e) => rightsOn(e, user?.userId, groupIds).contrib,
    ),
    canPublish: canPublishToPortal,
    searching: !!query,
    nextcloud: ncAvailable,
    cloudDrive: driveAvailable,
    integration: {
      lool:
        canOpenOnline &&
        selection.length === 1 &&
        selection[0].eType === "file" &&
        canOpenInLool(lool.data, selection[0]),
      scratch:
        flags.enableScratch &&
        selection.length === 1 &&
        canOpenInScratch(selection[0]),
      geogebra:
        flags.enableGeogebra &&
        selection.length === 1 &&
        canOpenInGeogebra(selection[0]),
    },
  });
  // Dernier élément coché : point de départ d'un Maj + clic
  const lastToggled = useRef<string | undefined>(undefined);
  const toggle = (id: string, range = false) => {
    const wanted = range
      ? rangeIds(
          visible.map((e) => e._id),
          lastToggled.current,
          id,
        )
      : [];
    setSelected((cur) => {
      const next = new Set(cur);
      if (wanted.length) wanted.forEach((w) => next.add(w));
      else if (!next.delete(id)) next.add(id);
      return next;
    });
    lastToggled.current = id;
  };
  const toggleAll = () =>
    setSelected((cur) =>
      cur.size === visible.length
        ? new Set()
        : new Set(visible.map((e) => e._id)),
    );

  /** Exécute une action serveur, rafraîchit la liste, l'arborescence et la jauge ; un échec est signalé. */
  // Retour positif d'une action (« mis à la corbeille », « restauré »…), qui s'efface seul
  const [success, setSuccess] = useState<string | null>(null);
  useEffect(() => {
    if (!success) return;
    const timer = window.setTimeout(() => setSuccess(null), 5000);
    return () => window.clearTimeout(timer);
  }, [success]);
  // message rapporté par une autre page (l'éditeur PDF après enregistrement), affiché une fois
  const returnedNotice = (location.state as { notice?: string } | null)?.notice;
  useEffect(() => {
    if (!returnedNotice) return;
    setSuccess(t(returnedNotice));
    void qc.invalidateQueries({ queryKey: ["workspace", "content"] });
    navigate(location.pathname, { replace: true, state: null });
  }, [returnedNotice]); // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (fn: () => Promise<unknown>, successKey?: string) => {
    setNotice(null);
    setSuccess(null);
    try {
      await fn();
      if (successKey) setSuccess(t(successKey));
      setSelected(new Set());
      await Promise.all(
        ["content", "folders", "quota"].map((k) =>
          qc.invalidateQueries({ queryKey: ["workspace", k] }),
        ),
      );
      return true;
    } catch {
      setNotice(t("workspace.react.action.failed"));
      return false;
    }
  };

  // Glisser-déposer interne : saisir un élément (ou la sélection) et le lâcher sur un dossier pour l'y déplacer
  const onDragStart = (el: WorkspaceElement, ev: globalThis.DragEvent) => {
    const items = draggedElements(el, selection);
    dragged.current = items;
    setMoving(true);
    ev.dataTransfer?.setData(DRAG_TYPE, encodeDrag(items));
    // depuis « Documents ajoutés dans les applis », lâcher sur un dossier COPIE (ces documents restent à leur place)
    if (ev.dataTransfer)
      ev.dataTransfer.effectAllowed =
        validTree === "protected" ? "copy" : "move";
  };
  const canDrop = (targetId: string | null, ev: globalThis.DragEvent) =>
    isInternalDrag([...(ev.dataTransfer?.types ?? [])]) &&
    canDropOn(targetId, dragged.current, folders.data ?? []);
  const moveDragged = (targetId: string | null) => {
    const items = dragged.current;
    dragged.current = [];
    if (!items.length) return;
    if (validTree === "protected")
      void run(
        () => elementActions.copy(items, targetId ?? undefined),
        "workspace.copy.window.finished",
      );
    else void run(() => elementActions.move(items, targetId ?? undefined));
  };
  // Lâcher sur « Corbeille » : mettre les éléments à la corbeille (Mes documents et applis)
  const trashDrop = {
    canDrop: (ev: globalThis.DragEvent) =>
      isInternalDrag([...(ev.dataTransfer?.types ?? [])]) &&
      dragged.current.length > 0,
    onDrop: () => {
      const items = dragged.current;
      dragged.current = [];
      if (items.length)
        void run(
          () => elementActions.trash(items),
          "workspace.removed.message",
        );
    },
  };

  const onAction = (a: ActionId) => {
    if ((TRANSFORM_IDS as string[]).includes(a)) setTransforming({ id: a as TransformId, items: selection });
    else if (a === "download") window.location.assign(downloadUrl(selection));
    // dans « Documents partagés », mettre à la corbeille supprime aussi pour les autres personnes qui voient ces éléments : on confirme
    else if (a === "trash")
      validTree === "shared"
        ? setConfirm("trashShared")
        : void run(
            () => elementActions.trash(selection),
            "workspace.removed.message",
          );
    else if (a === "restore")
      void run(
        () => elementActions.restore(selection),
        "workspace.restored.message",
      );
    else if (a === "delete") setConfirm("delete");
    else if (a === "share") {
      setShareSet(selection);
      (validTree === "owner" ? setWarnShare : setSharing)(selection[0]);
    } else if (a === "publish") setPublishing(selection[0]);
    else if (a === "history") setVersions(selection[0]);
    else if (a === "comments") setCommenting(selection[0]);
    else if (a === "location") navigate(locationOf(selection[0]));
    else if (a === "driveExport") void exportToDrive();
    else if (a === "ncCopy") setNcPicker("copy");
    else if (a === "ncMove") setNcPicker("move");
    else if (a === "editImage")
      navigate(`/view/${selection[0]._id}?edit=image`, {
        state: { ids: [selection[0]._id], from: location.pathname },
      });
    else if (a === "verifySignature") setCheckingSignature(selection[0]);
    else if (a === "editPdf")
      navigate(`/edit/pdf/${selection[0]._id}`, { state: { from: location.pathname } });
    else if (a === "openLool") window.open(loolUrl(selection[0]._id));
    else if (a === "openScratch") window.open(scratchUrl(selection[0]._id));
    else if (a === "openGeogebra") window.open(geogebraUrl(selection[0]));
    else if (a === "rename") {
      setRenameError(null);
      setRenaming(selection[0]);
    } else if (a === "copy" || a === "move") setPicker(a);
  };

  // F2 / Suppr sur un dossier de l'arbre « Mes documents » : renommer (fenêtre Propriétés) ou mettre à la corbeille
  const treeKeys = {
    onRename: (id: string) => {
      const f = (folders.data ?? []).find((x) => x._id === id);
      if (!f) return;
      setRenameError(null);
      setRenaming(f);
    },
    onDelete: (id: string) => {
      const f = (folders.data ?? []).find((x) => x._id === id);
      if (!f) return;
      void run(
        () => elementActions.trash([f]),
        "workspace.removed.message",
      ).then((ok) => {
        // on supprimait le dossier affiché (ou l'un de ses parents) : retour à son parent
        if (ok && (folderId === id || path.some((p) => p._id === id)))
          navigate(f.eParent ? `/owner/${f.eParent}` : "/owner");
      });
    },
  };

  // Raccourcis de l'ancienne interface : Ctrl+A tout sélectionner, Ctrl+C copier, Ctrl+X déplacer (jamais dans un champ de saisie,
  // ni quand une fenêtre est ouverte, ni quand du texte est sélectionné pour être copié)
  const shortcut = useRef<(ev: KeyboardEvent) => void>(() => undefined);
  shortcut.current = (ev) => {
    const which = shortcutOf(ev);
    if (
      !which ||
      isTypingTarget(ev.target) ||
      document.querySelector('[role="dialog"]')
    )
      return;
    if (which === "select-all") {
      ev.preventDefault();
      toggleAll();
    } else if (!window.getSelection()?.toString() && actions.includes(which)) {
      ev.preventDefault();
      setPicker(which);
    }
  };
  useEffect(() => {
    const listener = (ev: KeyboardEvent) => shortcut.current(ev);
    document.addEventListener("keydown", listener);
    return () => document.removeEventListener("keydown", listener);
  }, []);

  // Lecteur externe (Google Drive) : le consentement se demande dans le navigateur, aucun jeton ne passe par l'ENT.
  const [driveBusy, setDriveBusy] = useState(false);
  const importFromDrive = async () => {
    if (driveBusy) return;
    setNotice(null);
    try {
      const token = await requestAccessToken(flags.cloudDriveGoogleClientId);
      const picked = await openPicker(token, "files", flags);
      if (!picked.length) return;
      setDriveBusy(true);
      const files: File[] = [];
      for (const p of picked) {
        try {
          files.push(await downloadFromDrive(p, token));
        } catch {
          setNotice(`${t("workspace.cloud.drive.import.error.one")} ${p.name}`);
        }
      }
      if (files.length) {
        await uploads.send(files);
        setNotice((n) => n ?? t("workspace.cloud.drive.import.success"));
      }
    } catch {
      setNotice(t("workspace.cloud.drive.error"));
    } finally {
      setDriveBusy(false);
    }
  };
  const exportToDrive = async () => {
    if (driveBusy) return;
    setNotice(null);
    const files = selection.filter((e) => e.eType === "file");
    try {
      const token = await requestAccessToken(flags.cloudDriveGoogleClientId);
      const folders = await openPicker(token, "folder", flags);
      setDriveBusy(true);
      let failed = "";
      for (const f of files) {
        try {
          await uploadToDrive(f._id, f.name, token, folders[0]?.id);
        } catch {
          failed = `${t("workspace.cloud.drive.export.error.one")} ${f.name}`;
        }
      }
      setNotice(failed || t("workspace.cloud.drive.export.success"));
    } catch {
      setNotice(t("workspace.cloud.drive.error"));
    } finally {
      setDriveBusy(false);
    }
  };

  const zipTarget = useRef<string | null | undefined>(undefined);
  /** `target` : dossier de destination quand les fichiers sont lâchés sur l'arbre (`null` = « Mes documents ») ; absent = dossier courant. */
  const importFiles = (list: File[], target?: string | null) => {
    setNotice(null);
    const maxSize = parseInt(t("max.file.size"), 10) || 0;
    const plan = planUpload(list, quota.data, maxSize);
    const messages = new Set<string>();
    for (const r of plan.rejected) {
      messages.add(
        r.reason === "too-large"
          ? `${t("file.too.large.limit")}${Math.round(maxSize / 1024 / 1024)} ${t("mb")}`
          : `${t("file.too.large.max")}${remainingLabel(quota.data)}`,
      );
    }
    if (messages.size) setNotice([...messages].join(" "));
    const zips = plan.accepted.filter(isZip);
    const others = plan.accepted.filter((f) => !isZip(f));
    if (others.length) void uploads.send(others, false, target);
    if (zips.length) {
      zipTarget.current = target;
      setPendingZip(zips[0]);
    }
  };

  const onDrop = (e: DragEvent) => {
    if (isInternalDrag([...e.dataTransfer.types])) return;
    e.preventDefault();
    setDragging(false);
    if (!canWrite || !mayAddDocument) return;
    const items = [...e.dataTransfer.items];
    // Un dossier déposé ne peut pas être importé : seuls les fichiers le sont (comme dans l'ancienne interface).
    if (items.some((i) => i.webkitGetAsEntry?.()?.isDirectory)) {
      setNotice(
        `${t("workspace.import.warning")}. ${t("workspace.import.solution").replace(/<[^>]+>/g, "")}`,
      );
      return;
    }
    importFiles([...e.dataTransfer.files]);
  };

  /** Fichiers du poste lâchés sur un dossier de l'arbre : envoyés dans ce dossier, sans quitter la page courante. */
  const onFilesOnTree = (targetId: string | null, e: globalThis.DragEvent) => {
    const dt = e.dataTransfer;
    if (!dt) return;
    if ([...dt.items].some((i) => i.webkitGetAsEntry?.()?.isDirectory)) {
      setNotice(
        `${t("workspace.import.warning")}. ${t("workspace.import.solution").replace(/<[^>]+>/g, "")}`,
      );
      return;
    }
    importFiles([...dt.files], targetId);
  };

  const path = useMemo(
    () => breadcrumb(folders.data ?? [], folderId ?? null),
    [folders.data, folderId],
  );

  if (!isTreeName(tree)) return <Navigate to="/owner" replace />;

  return (
    <>
      <AppHeader
        render={() =>
          canWrite && mayAddDocument ? (
            <div className="d-flex flex-wrap gap-8" data-workspace-toolbar>
              {(mayAddFolder || canCreateOnline) && (
                <Dropdown>
                  {/* Mêmes icônes que l'ancienne interface : carré « + » pour Nouveau, nuage et flèche pour Importer. */}
                  <Dropdown.Trigger
                    label={t("workspace.react.new")}
                    variant="secondary"
                    icon={<IconAdd width={20} height={20} />}
                    data-toolbar-new
                  />
                  <Dropdown.Menu>
                    {mayAddFolder && (
                      <Dropdown.Item icon={<IconFolderAdd />} onClick={() => setNewFolderOpen(true)} data-toolbar-new-folder>
                        {t("folder.new")}
                      </Dropdown.Item>
                    )}
                    {canCreateOnline && (
                      <Dropdown.Item icon={<IconTextPage />} onClick={() => setLoolCreate(true)} data-action="lool-create">
                        {t("workspace.lool.create-document")}
                      </Dropdown.Item>
                    )}
                    {driveAvailable && (
                      <Dropdown.Item icon={<IconUpload />} disabled={driveBusy} onClick={() => void importFromDrive()} data-action="drive-import">
                        {t("workspace.cloud.drive.import.action")}
                      </Dropdown.Item>
                    )}
                  </Dropdown.Menu>
                </Dropdown>
              )}
              <Button
                type="button"
                color="primary"
                leftIcon={<IconDownload width={20} height={20} />}
                onClick={() => fileInput.current?.click()}
                data-action="import"
              >
                {t("import.file")}
              </Button>
              <input
                ref={fileInput}
                type="file"
                multiple
                hidden
                data-import-input
                onChange={(e) => {
                  importFiles([...(e.target.files ?? [])]);
                  e.target.value = "";
                }}
              />
            </div>
          ) : null
        }
      >
        <h1 className="h2" data-workspace-title>
          {t("workspace.title")}
        </h1>
      </AppHeader>
      <div className="d-flex gap-24 flex-wrap">
        <aside className="ws-aside">
          <TreeNav
            tree={validTree}
            folderId={folderId}
            folders={folders.data ?? []}
            publicVisible={(publicRoot.data ?? []).length > 0}
            onCreateFolder={canWrite && mayAddFolder ? () => setNewFolderOpen(true) : undefined}
            drop={
              validTree === "owner" || validTree === "protected"
                ? {
                    canDrop,
                    onDrop: (id) => moveDragged(id),
                    trash: trashDrop,
                    files:
                      validTree === "owner"
                        ? { onDrop: onFilesOnTree }
                        : undefined,
                  }
                : undefined
            }
            keys={validTree === "owner" ? treeKeys : undefined}
            userId={user?.userId}
            lazy={lazy}
          >
            {quota.data && <QuotaGauge quota={quota.data} />}
          </TreeNav>
          {ncAvailable && (
            <NextcloudNav
              userId={user?.userId}
              active={false}
              trash={false}
              folders={(ncRoot.data ?? []).filter((d) => d.isFolder)}
              currentPath=""
              quota={ncQuota.data}
            />
          )}
        </aside>
        <main
          className={`ws-main${dragging && canWrite ? " ws-drop-target" : ""}`}
          data-workspace-dropzone
          onDragOver={(e) => {
            if (isInternalDrag([...e.dataTransfer.types])) return;
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
        >
          <nav aria-label={t("workspace.react.breadcrumb")} data-workspace-breadcrumb>
            <ol className="breadcrumb mb-8">
              <li className={`breadcrumb-item${path.length === 0 ? " active" : ""}`}>
                <Link to={`/${validTree}`}>{t(TREE_LABEL_KEY[validTree])}</Link>
              </li>
              {path.map((f, i) => (
                <li key={f._id} className={`breadcrumb-item${i === path.length - 1 ? " active" : ""}`}>
                  <span className="ws-crumb-sep" aria-hidden="true">
                    /
                  </span>
                  <Link to={`/${validTree}/${f._id}`}>{f.name}</Link>
                </li>
              ))}
            </ol>
          </nav>
          <h2 className="h3 mb-16" data-workspace-location>
            {heaviest
              ? t("workspace.react.quota.heaviest")
              : path.length
                ? path[path.length - 1].name
                : t(TREE_LABEL_KEY[validTree])}
          </h2>
          <QuotaAlert quota={quota.data} onShowHeaviest={showHeaviest} onEmptyTrash={() => setConfirm("empty")} />
          <div
            className="d-flex flex-wrap gap-16 align-items-center justify-content-between mb-16"
            data-workspace-header
          >
            <form
              className="flex-grow-1"
              style={{ maxWidth: 420 }}
              role="search"
              data-workspace-search
              onSubmit={(e) => {
                e.preventDefault();
                if (criteria.trim()) setQuery({ criteria: criteria.trim(), everywhere: false });
              }}
            >
              {/* Le bouton de recherche du socle est de type « submit » : il déclenche l'envoi du formulaire,
                  tout comme la touche Entrée dans le champ. */}
              <SearchBar
                isVariant={false}
                value={criteria}
                placeholder={t("workspace.search.placeholder")}
                aria-label={t("workspace.search.placeholder")}
                onChange={(e) => setCriteria(e.target.value)}
                onClick={() => {
                  if (criteria.trim()) setQuery({ criteria: criteria.trim(), everywhere: false });
                }}
                data-search-input
              />
            </form>
            <div className="ws-view-controls d-flex flex-wrap gap-12 align-items-center ms-auto">
              <span className="text-gray-700">{t("workspace.react.sort")}</span>
              <span data-workspace-sort>
                <Select
                  options={sortOptions}
                  selectedValue={currentSortOption}
                  placeholderOption={t("workspace.react.sort")}
                  onValueChange={(option) => {
                    const next = sortOf(typeof option === "string" ? option : option.value);
                    // Le composant du socle annonce aussi la valeur courante au montage : on ignore ce qui ne
                    // change rien, sinon on réécrirait la préférence de l'usager à chaque arrivée sur la page.
                    if (next.key === sort.key && next.desc === sort.desc) return;
                    changeSort(next.key, false, next.desc);
                  }}
                />
              </span>
              <SegmentedControl
                options={viewOptions}
                value={view}
                onChange={(v: string) =>
                  changeView(v === "list" ? "list" : v === "carousel" ? "carousel" : "icons")
                }
              />
            </div>
          </div>
          {query && (
            <div className="mb-16" data-search-banner>
              <strong>
                {query.everywhere
                  ? t("workspace.search.results.inworkspace")
                  : `${t("workspace.search.results.infolder")} ${path.length ? path[path.length - 1].name : t(TREE_LABEL_KEY[validTree])}`}
              </strong>
              {!query.everywhere && (
                <button
                  type="button"
                  className="btn btn-tertiary btn-sm ms-8"
                  data-search-everywhere
                  onClick={() => setQuery({ ...query, everywhere: true })}
                >
                  {t("workspace.search.inworkspace")}
                </button>
              )}
              <button
                type="button"
                className="btn btn-tertiary btn-sm ms-8"
                data-search-reset
                onClick={() => {
                  setQuery(null);
                  setCriteria("");
                }}
              >
                {t("workspace.react.search.reset")}
              </button>
              {search.isFetching && !searchDegraded && <span> — {t("workspace.search.inprogress")}</span>}
              {searchDegraded && !flags.disableFullTextSearch && (
                <p className="text-warning mb-0" role="status" data-search-degraded>
                  {t("workspace.react.search.degraded")}
                </p>
              )}
            </div>
          )}
          {heaviest && (
            <div className="mb-16" data-heaviest-banner>
              <strong>{t("workspace.react.quota.heaviest")}</strong>
              <button type="button" className="btn btn-tertiary btn-sm ms-8" data-heaviest-reset onClick={() => setHeaviest(false)}>
                {t("workspace.react.search.reset")}
              </button>
            </div>
          )}
          <TypeFilters counts={typeCounts} value={typeFilter} onChange={setTypeFilter} />
          <ActionBar
            actions={actions}
            count={selection.length}
            sizeLabel={selectionSize > 0 ? formatSize(selectionSize) : undefined}
            onAction={onAction}
            onClear={() => setSelected(new Set())}
          />
          {validTree === "trash" && elements.length > 0 && (
            <div className="mb-16">
              <button
                type="button"
                className="btn btn-filled btn-danger"
                data-action="empty-trash"
                onClick={() => setConfirm("empty")}
              >
                {t("workspace.empty.trash")}
              </button>
            </div>
          )}
          {success && (
            <div
              className="alert alert-success"
              role="status"
              data-workspace-success
            >
              {success}
            </div>
          )}
          {notice && (
            <div
              className="alert alert-warning"
              role="alert"
              data-workspace-notice
            >
              {notice}
            </div>
          )}
          <UploadPanel items={uploads.items} onClear={uploads.clear} />
          {listed.isLoading && <LoadingScreen />}
          {listed.isError && (
            <div
              className="alert alert-danger"
              role="alert"
              data-workspace-error
            >
              {t("workspace.react.error")}{" "}
              <button
                type="button"
                className="btn btn-tertiary btn-sm"
                onClick={() => void listed.refetch()}
              >
                {t("workspace.react.retry")}
              </button>
            </div>
          )}
          {listed.isSuccess && elements.length === 0 && (
            <div className="my-24" data-workspace-empty data-empty-image>
              <EmptyScreen
                imageSrc="/workspace/public/img/empty-workspace.svg"
                title={t(empty.title)}
                text={empty.text ? t(empty.text) : undefined}
              />
            </div>
          )}
          {listed.isSuccess && elements.length > 0 && (
            <>
              {view === "carousel" ? (
                <Carousel elements={elements} />
              ) : (
                <ElementList
                  tree={validTree}
                  elements={visible}
                  view={view}
                  selected={selected}
                  onToggle={toggle}
                  onToggleAll={toggleAll}
                  sort={sort}
                  onSort={(key) => changeSort(key)}
                  publishedIds={published.data}
                  searching={!!query}
                  onShowComments={setCommenting}
                  onDragStart={
                    validTree === "owner" || validTree === "protected"
                      ? onDragStart
                      : undefined
                  }
                  onDropOnFolder={
                    validTree === "owner"
                      ? (f) => moveDragged(f._id)
                      : undefined
                  }
                  canDropOn={(f, ev) => canDrop(f._id, ev)}
                  onOpenFile={(el, siblings) => setViewing({ id: el._id, siblings })}
                />
              )}
              <LoadMore
                remaining={elements.length - visible.length}
                paused={moving}
                onMore={() => setShown((n) => n + PAGE_SIZE)}
              />
            </>
          )}
        </main>
      </div>
      <ShareWarning
        element={warnShare}
        busy={copyingForShare}
        onClose={() => setWarnShare(null)}
        onShare={() => {
          setSharing(warnShare);
          setWarnShare(null);
        }}
        onShareCopy={async () => {
          if (!warnShare) return;
          const originals = shareSet.length ? shareSet : [warnShare];
          setCopyingForShare(true);
          try {
            const copies = await elementActions.copy(originals);
            // chaque copie remplace son original, dans le même ordre
            const copied = originals
              .map((o) => {
                const copy = copies.find((c) => c.copyFromId === o._id);
                return copy ? { ...o, ...copy } : undefined;
              })
              .filter((c): c is WorkspaceElement => !!c);
            await Promise.all(
              ["content", "folders", "quota"].map((k) =>
                qc.invalidateQueries({ queryKey: ["workspace", k] }),
              ),
            );
            if (copied.length) {
              setShareSet(copied);
              setSharing(copied[0]);
            }
            setWarnShare(null);
          } catch {
            setNotice(t("workspace.react.action.failed"));
            setWarnShare(null);
          } finally {
            setCopyingForShare(false);
          }
        }}
      />
      {sharing && (
        <ShareModal
          isOpen
          shareOptions={{
            resourceId: sharing._id,
            resourceRights: [],
            resourceCreatorId: sharing.owner,
            resourceCreatorDisplayName: sharing.ownerName,
          }}
          onCancel={() => setSharing(null)}
          onSuccess={() => {
            const first = sharing;
            const others = shareSet
              .filter((e) => e._id !== first._id)
              .map((e) => e._id);
            // plusieurs éléments : les droits réglés sur le premier sont recopiés sur les autres, puis on rafraîchit
            const copied = others.length
              ? elementActions
                  .shareLike(first._id, others)
                  .catch(() => setNotice(t("workspace.react.action.failed")))
              : Promise.resolve();
            setSharing(null);
            setSelected(new Set());
            void copied.then(() =>
              Promise.all(
                ["content", "folders"].map((k) =>
                  qc.invalidateQueries({ queryKey: ["workspace", k] }),
                ),
              ),
            );
          }}
        />
      )}
      <NextcloudPicker
        isOpen={ncPicker !== null}
        userId={user?.userId}
        title={t(
          ncPicker === "move"
            ? "nextcloud.share.move.action"
            : "nextcloud.share.copy.action",
        )}
        onClose={() => setNcPicker(null)}
        onPick={(dest) => {
          const verb = ncPicker === "move" ? "move" : "copy";
          setNcPicker(null);
          if (user?.userId)
            void run(() =>
              nextcloudService.fromWorkspace(
                user.userId,
                verb,
                selection.map((e) => e._id),
                dest || undefined,
              ),
            );
        }}
      />
      <LoolCreateModal
        isOpen={loolCreate}
        folderId={folderId}
        onClose={() => setLoolCreate(false)}
        onResync={() =>
          void Promise.all(
            ["content", "folders", "quota"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          )
        }
      />
      <SignatureCheckModal element={checkingSignature} onClose={() => setCheckingSignature(null)} />
      <TransformModal
        transform={transforming?.id ?? null}
        selection={transforming?.items ?? []}
        // le résultat va dans le dossier courant de « Mes documents », sinon à sa racine
        parentId={validTree === "owner" ? folderId : undefined}
        canVersion={
          !!transforming &&
          transforming.items.length === 1 &&
          (validTree === "owner" || rightsOn(transforming.items[0], user?.userId, groupIds).contrib)
        }
        onClose={() => setTransforming(null)}
        onDone={(result) => {
          setTransforming(null);
          setNotice(null);
          setSuccess(t(result.version ? "workspace.transform.done.version" : "workspace.transform.done"));
          setSelected(new Set(result._id && !result.version && validTree === "owner" ? [result._id] : []));
          void Promise.all(
            ["content", "folders", "quota"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          );
        }}
      />
      <VersionsModal
        element={versions}
        rights={
          versions
            ? rightsOn(versions, user?.userId, groupIds)
            : { read: false, contrib: false, manager: false, comment: false }
        }
        userId={user?.userId}
        onClose={() => setVersions(null)}
        onChanged={() =>
          void Promise.all(
            ["content", "quota"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          )
        }
      />
      <CommentsModal
        element={commenting}
        rights={
          commenting
            ? rightsOn(commenting, user?.userId, groupIds)
            : { read: false, contrib: false, manager: false, comment: false }
        }
        userId={user?.userId}
        userName={user?.username}
        onClose={() => setCommenting(null)}
        onChanged={() =>
          void qc.invalidateQueries({ queryKey: ["workspace", "content"] })
        }
      />
      <PublishModal
        element={publishing}
        published={!!publishing && !!published.data?.includes(publishing._id)}
        onClose={() => setPublishing(null)}
        onChanged={() =>
          void Promise.all(
            ["content", "published"].map((k) =>
              qc.invalidateQueries({ queryKey: ["workspace", k] }),
            ),
          )
        }
      />
      {picker && (
        <TransferDialog
          mode={picker}
          elements={selection}
          folders={folders.data ?? []}
          userId={user?.userId}
          groupIds={groupIds}
          fromTree={validTree}
          lazy={lazy}
          onClose={() => setPicker(null)}
          onDone={() => {
            setPicker(null);
            setSelected(new Set());
            void Promise.all(
              ["content", "folders", "quota"].map((k) =>
                qc.invalidateQueries({ queryKey: ["workspace", k] }),
              ),
            );
          }}
        />
      )}
      <RenameModal
        element={renaming}
        error={renameError}
        parentHref={(parentId) =>
          `#/${validTree === "shared" ? "shared" : "owner"}/${parentId}`
        }
        onClose={() => setRenaming(null)}
        onRename={async (name) => {
          if (!renaming) return;
          const target = renaming;
          if (await run(() => elementActions.rename(target, name)))
            setRenaming(null);
          else setRenameError(t("workspace.react.action.failed"));
        }}
      />
      <ConfirmModal
        isOpen={confirm !== null}
        title={
          confirm === "empty"
            ? t("workspace.empty.trash")
            : confirm === "trashShared"
              ? t("workspace.delete.confirm.title")
              : t("workspace.react.action.delete")
        }
        message={
          confirm === "empty"
            ? t("workspace.delete.empty.trash")
            : confirm === "trashShared"
              ? t("workspace.react.confirm.trash.shared")
              : t("workspace.react.confirm.delete")
        }
        confirmLabel={
          confirm === "empty"
            ? t("workspace.empty.trash")
            : confirm === "trashShared"
              ? t("workspace.delete.confirm.action")
              : t("workspace.react.action.delete")
        }
        onClose={() => setConfirm(null)}
        onConfirm={() => {
          const mode = confirm;
          setConfirm(null);
          if (mode === "trashShared")
            void run(
              () => elementActions.trash(selection),
              "workspace.removed.message",
            );
          else
            void run(
              () =>
                mode === "empty"
                  ? elementActions.emptyTrash()
                  : elementActions.deleteForever(selection),
              mode === "empty"
                ? "workspace.empty.trash.confirm"
                : "workspace.deleted.message",
            );
        }}
      />
      <NewFolderModal
        parentId={folderId}
        isOpen={newFolderOpen}
        onClose={() => setNewFolderOpen(false)}
      />
      <ViewerModal
        id={viewing?.id}
        siblings={viewing?.siblings ?? []}
        onClose={() => setViewing(null)}
        onCurrentChange={(next) => setViewing((v) => (v ? { ...v, id: next } : v))}
      />
      <ZipDialog
        file={pendingZip}
        onClose={() => setPendingZip(null)}
        onChoose={(uncompress) => {
          const file = pendingZip;
          setPendingZip(null);
          if (file) void uploads.send([file], uncompress, zipTarget.current);
        }}
      />
    </>
  );
};
