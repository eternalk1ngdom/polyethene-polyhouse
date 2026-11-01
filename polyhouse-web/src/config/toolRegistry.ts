/** 首页、导航及工具说明共用元数据，避免新增工具时多处维护展示文案。 */
export interface ToolRegistryItem {
  /** 稳定标识用于选择页面，不随中文名称变化。 */
  code: string
  /** 对外显示的工具名称。 */
  title: string
  /** 统一维护入口，保证首页和导航指向同一地址。 */
  route: string
  /** 图标名由展示层映射到 Element Plus 图标，配置不引入组件。 */
  icon: 'Switch' | 'Crop'
  /** 简短分类帮助用户快速区分用途。 */
  tag: string
  /** 英文副标题作为辅助标识。 */
  subtitle: string
  /** 描述目标用途，不承诺尚未交付的处理能力。 */
  description: string
  /** 显式区分说明页和可用工具，避免误导用户上传文件。 */
  status: 'planned' | 'available'
  /** 语义化配色由组件解释，不在元数据中保存 CSS。 */
  accent: 'lilac' | 'peach'
  /** 分类与封面共用语义类别，不根据中文文案猜测筛选。 */
  category: 'text' | 'image'
  /** 近期计划用于说明当前交付边界。 */
  plannedFeatures: readonly string[]
}

/** 工具入口的唯一清单；使用状态必须与实际已开放能力一致。 */
export const toolRegistry: readonly ToolRegistryItem[] = [
  {
    code: 'poly-replace',
    title: '妙替',
    route: '/replace',
    icon: 'Switch',
    tag: '文本替换',
    subtitle: 'PolyReplace',
    description: '在浏览器本地批量替换 UTF-8 文本文件或小型文件夹，预览并下载结果。',
    status: 'available',
    accent: 'lilac',
    category: 'text',
    plannedFeatures: ['多组规则，一次替换', '文件与目录批量处理', '结果预览与文件导出'],
  },
  {
    code: 'target-kb',
    title: '妙压',
    route: '/targetkb',
    icon: 'Crop',
    tag: '图片处理',
    subtitle: 'TargetKB',
    description: '为照片找到刚刚好的大小，让那些挑剔的文件限制简单一点。',
    status: 'planned',
    accent: 'peach',
    category: 'image',
    plannedFeatures: ['按目标体积压缩', '小图片无损增重', '尺寸、DPI 与常用规格'],
  },
]
