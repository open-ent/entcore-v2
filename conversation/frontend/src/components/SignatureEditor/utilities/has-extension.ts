import { EditorInstance } from '@open-ent/react/editor';

export const hasExtension = (
  extensionName: string,
  editor: EditorInstance | null,
) =>
  !!editor?.extensionManager.extensions.find(
    (item) => item.name === extensionName,
  );
