/** 日报是独立阅读模块。首页和导航复用入口描述，不把它归成文本或图片工具。 */
export const starTide = {
  name: 'star-tide',
  route: '/trend',
  ownerRoute: '/manage/trend',
  title: '星潮',
  subtitle: 'StarTide',
  tag: '开源项目日报',
  description: '每日 GitHub 热门项目与中文解读',
} as const
