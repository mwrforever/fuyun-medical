/**
 * commitlint 配置：提交信息遵循 conventional commits 规则集（type 范围、scope、描述格式等全量规则）。
 * 规则来源：@commitlint/config-conventional 官方预设，零自定义起步；
 * CI 由 ci.yml commitlint job 校验，本地经 husky commit-msg 挂接同规则（见 web/AGENTS.md C.6），两端同源。
 * 规则细化（如 scope 枚举业务模块名）属演进项，需先记 CHANGELOG.md 再修订。
 */
export default {
  extends: ['@commitlint/config-conventional'],
}
