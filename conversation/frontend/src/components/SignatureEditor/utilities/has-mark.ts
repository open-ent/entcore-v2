import { EditorInstance } from '@open-ent/react/editor';

export const hasMark = (extensionName: string, editor: EditorInstance | null) =>
  !!editor?.extensionManager.splittableMarks.includes(extensionName);
