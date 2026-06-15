# Media Center 分类视频种子数据

经过 B站抓取 + AI 审查 + 播放预检验证的分类视频列表。每条数据已验证可在 B 站正常播放（playurl API 通过）。

## 文件清单

| 分类 | 文件 | 数量 | 数据来源 |
|------|------|------|----------|
| movies | `categories/movies.json` | 600 | 豆瓣 Top 250 电影匹配 + B站搜索 |
| tv-series | `categories/tv-series.json` | 510 | 豆瓣国产剧+美剧+英剧+韩剧+日剧匹配 |
| family-theater | `categories/family-theater.json` | 232 | 豆瓣家庭剧 + 经典家庭剧 B站搜索 |
| red-classic | `categories/red-classic.json` | 523 | B站红色经典搜索（纪录片+政史内容） |
| dota-zone | `categories/dota-zone.json` | 516 | B站 Dota 比赛/录像/集锦搜索 |

> family-theater 数量较少是因为 B 站上"家庭生活剧"高质量资源客观有限。

## 数据格式

每个种子文件的顶层格式固定为导入信封（与 `POST /internal/catalog/import` API 完全一致）：

```json
{
  "items": [
    {
      "categoryId": "family-theater",
      "provider": "bilibili",
      "nativeId": "BV1...",
      "title": "标题"
    }
  ]
}
```

不使用顶层数组；admin 页面、admin API 和 media_server 内部导入接口都只接受这一种格式。

`items` 内每条数据字段：

- `categoryId` — 分类 ID
- `provider` — 数据来源（固定 `bilibili`）
- `nativeId` — B 站 BV 号
- `title` / `description` / `creators` — 元数据
- `durationSeconds` — 时长
- `thumbnailUrl` / `sourceUrl` — 缩略图/原链接
- `score` — 评分（豆瓣评分 × 10，或人工/工具排序参考分）
- `status` — 可选，`pending` 或 `approved`；省略时导入为待审

## 部署流程

1. **导入种子**：在 admin 页面“导入视频集”粘贴本目录 JSON 文件内容，或在服务器上用 `POST /api/admin/media-center/catalog/import` / `POST /internal/catalog/import` 调用，body 为本目录 JSON 文件原文
2. **审核**：用 `POST /internal/catalog/review` approve，或 `media_server -catalog-review-auto` 自动审核
3. **快照重建**：用 `POST /internal/snapshots/rebuild` 触发

调用示例（admin 容器内）：

```bash
# 上传 JSON 到 media_server 容器（或通过 admin 容器内卷共享）
# 然后分批调用 import API（每批 ≤ 1000 条）
```

## 数据过期与重新验证

B 站视频可能下架、付费、地区限制。建议：

- 每月用 playurl API 抽样验证可用性
- 失效的视频从种子中移除
- 用同搜索策略补充新视频
- 重新生成时由 `workwork` 仓库 `.local/media-curation/` 下的脚本驱动

## 数据来源追溯

种子数据生成自 `workwork` 仓库的 `.local/media-curation/` 脚本：

- `harvest.py` — B站搜索抓取
- `enrich.py` — 内容丰富（view + tag API）
- `deep_analyze.py` — 深度分类分析
- `douban_harvest.py` / `douban_family_match.py` — 豆瓣补充
- `playcheck_full.py` — 播放预检（playurl API）
- AI 审查（结合 B站 tag + 标题语义 + 豆瓣评分）

原始过程数据（搜索候选、抓取中间结果）不入库，仅 final 数据（AI 审查+playcheck 验证后）入库。
