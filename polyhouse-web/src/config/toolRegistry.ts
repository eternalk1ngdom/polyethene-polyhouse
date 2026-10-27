/** 首页、导航及工具说明共用元数据，避免新增工具时多处维护展示文案。 */
export interface ToolRegistryItem {
  /** 稳定标识用于选择页面，不随中文名称变化。 */
  code: string
  /** 对外显示的工具名称。 */
  title: string
  /** 统一维护入口，保证首页和导航指向同一地址。 */
  route: string
  /** 图标名由展示层映射到 Element Plus 图标，配置不引入组件。 */
  icon: 'Document' | 'Picture'
  /** 简短分类帮助用户快速区分用途。 */
  tag: string
  /** 英文副标题作为辅助标识。 */
  subtitle: string
  /** 描述目标用途，不承诺尚未交付的处理能力。 */
  description: string
  /** 显式区分说明页和可用工具，避免误导用户上传文件。 */
  status: 'planned' | 'available'
  /** 语义化配色由组件解释，不在元数据中保存 CSS。 */
  accent: 'mint' | 'apricot'
  /** 近期计划用于说明当前交付边界。 */
  plannedFeatures: readonly string[]
}

/** 工具入口的唯一清单；目前两个入口均展示开发说明。 */
export const toolRegistry: readonly ToolRegistryItem[] = [
  {
    code: 'poly-replace',
    title: '妙替',
    route: '/replace',
    icon: 'Document',
    tag: '文本替换',
    subtitle: 'PolyReplace',
    description: '从一段文字到一整个文件夹，让重复的替换工作更轻松。',
    status: 'planned',
    accent: 'mint',
    plannedFeatures: ['多组规则，一次替换', '文件与目录批量处理', '结果预览与文件导出'],
  },
  {
    code: 'target-kb',
    title: '妙压',
    route: '/targetkb',
    icon: 'Picture',
    tag: '图片处理',
    subtitle: 'TargetKB',
    description: '为照片找到刚刚好的大小，让那些挑剔的文件限制简单一点。',
    status: 'planned',
    accent: 'apricot',
    plannedFeatures: ['按目标体积压缩', '小图片无损增重', '尺寸、DPI 与常用规格'],
  },
]
