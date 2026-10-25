/** Describes one tool entry without coupling the registry to a UI component. */
export interface ToolRegistryItem {
  code: string
  title: string
  route: string
  icon: string
  tag: string
}

export const toolRegistry: readonly ToolRegistryItem[] = []
