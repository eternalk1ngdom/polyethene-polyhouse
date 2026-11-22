/**
 * GitHub Linguist 官方主流编程语言标识色（纯前端静态映射，大小写不敏感）。
 * 缺失语言与未收录冷门语言采用区分度清晰的中性灰蓝兜底，不混淆为具体语言颜色。
 */
const githubLanguageColors: Record<string, string> = {
  assembly: '#6E4C13',
  astro: '#ff5a03',
  batchfile: '#C1F12E',
  blade: '#f7523f',
  c: '#555555',
  'c#': '#178600',
  'c++': '#f34b7d',
  cairo: '#ff4a48',
  clojure: '#db5855',
  cmake: '#DA3434',
  coffeescript: '#244776',
  css: '#663399',
  cuda: '#3A4E3A',
  dart: '#00B4AB',
  dockerfile: '#384d54',
  elixir: '#6e4a7e',
  'emacs lisp': '#c065db',
  erlang: '#B83998',
  'f#': '#b845fc',
  gdscript: '#355570',
  gleam: '#ffaff3',
  glsl: '#5686a5',
  go: '#00ADD8',
  groovy: '#4298b8',
  haskell: '#5e5086',
  hcl: '#844FBA',
  html: '#e34c26',
  java: '#b07219',
  javascript: '#f1e05a',
  json: '#292929',
  julia: '#a270ba',
  'jupyter notebook': '#DA5B0B',
  kotlin: '#A97BFF',
  lua: '#000080',
  makefile: '#427819',
  markdown: '#083fa1',
  matlab: '#e16737',
  mdx: '#fcb32c',
  mojo: '#ff4c1f',
  move: '#4a137a',
  nix: '#7e7eff',
  'objective-c': '#438eff',
  'objective-c++': '#6866fb',
  ocaml: '#ef7a08',
  perl: '#0298c3',
  php: '#4F5D95',
  plpgsql: '#336790',
  powershell: '#012456',
  python: '#3572A5',
  r: '#198CE7',
  ruby: '#701516',
  rust: '#dea584',
  scala: '#c22d40',
  scss: '#c6538c',
  shaderlab: '#222c37',
  shell: '#89e051',
  solidity: '#AA6746',
  sql: '#e38c00',
  svelte: '#ff3e00',
  swift: '#F05138',
  tex: '#3D6117',
  toml: '#9c4221',
  twig: '#c1d026',
  typescript: '#3178C6',
  typst: '#239dad',
  v: '#4f87c4',
  'vim script': '#199f4b',
  'visual basic .net': '#945db7',
  vue: '#41b883',
  webassembly: '#04133b',
  wgsl: '#1a5e9a',
  yaml: '#cb171e',
  zig: '#ec915c',
}

const UNKNOWN_LANGUAGE_COLOR = '#b7c3ca'
const FALLBACK_LANGUAGE_COLOR = '#6e7f8d'

/** 返回语言对应的 GitHub 标识色；语言为空返回淡灰，冷门未收录语言返回中性灰蓝。 */
export function languageColor(language: string | null): string {
  if (!language || !language.trim()) return UNKNOWN_LANGUAGE_COLOR
  const key = language.trim().toLowerCase()
  return Object.hasOwn(githubLanguageColors, key)
    ? githubLanguageColors[key]!
    : FALLBACK_LANGUAGE_COLOR
}
