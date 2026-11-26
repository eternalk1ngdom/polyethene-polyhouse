/**
 * 星潮 API 兼容汇总入口：按子域拆分为 trendReport / trendBriefing / trendAdmin，
 * 保留原导入路径零破坏；后续单子域维护可直接读写对应子模块。
 */
export { isTrendDate, isTrendId } from './trendCodec'
export * from './trendReport'
export * from './trendBriefing'
export * from './trendAdmin'
