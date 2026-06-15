# Media Center 分类视频种子数据

各分类的**候选视频列表**(粗略搜索结果 / AI 收集汇总)。这些数据只是**粗筛候选**,
导入后统一为 `pending`,最终是否上线由 **admin 页面的人工审核 + AI 模型判断**决定
(approve / reject)。**不依赖脚本或代码做精准筛选或播放预检**——精准筛选交给人工与 AI。

## 文件清单

| 分类 | 文件 | 数量 | 数据来源 |
|------|------|------|----------|
| movies | `categories/movies.json` | 600 | B 站搜索 + 公开榜单匹配 |
| tv-series | `categories/tv-series.json` | 510 | B 站搜索 + 公开榜单匹配 |
| family-theater | `categories/family-theater.json` | 232 | B 站家庭剧搜索 |
| red-classic | `categories/red-classic.json` | 523 | B 站红色经典搜索 |
| dota-zone | `categories/dota-zone.json` | 516 | B 站 Dota 比赛/录像/集锦搜索 |

> 数量只反映候选规模,不代表全部可播放;失效/不合适的条目由人工与 AI 在 admin 复核时剔除。

## 数据格式

每个种子文件的顶层格式固定为导入信封(与 `POST /internal/catalog/import` API 完全一致):

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

不使用顶层数组;admin 页面、admin API 和 media_server 内部导入接口都只接受这一种格式。

`items` 内每条数据字段:

- `categoryId` — 分类 ID
- `provider` — 数据来源(固定 `bilibili`)
- `nativeId` — B 站 BV 号
- `title` / `description` / `creators` — 元数据
- `durationSeconds` — 时长
- `thumbnailUrl` / `sourceUrl` — 缩略图/原链接
- `score` — 可选排序参考分(人工/AI 给出),仅供审校时参考,不参与任何代码筛选
- `status` — 可选,`pending` 或 `approved`;省略时导入为待审(`pending`)

## 部署流程

1. **导入候选**:在 admin 页面“导入视频集”粘贴本目录 JSON 文件内容,或调用
   `POST /api/admin/media-center/catalog/import` / `POST /internal/catalog/import`
   (body 为本目录 JSON 文件原文)。重复导入只新增候选,绝不覆盖已有的审核决定。
2. **审核(人工 + AI)**:在 admin 页面对 `pending` 候选逐条 approve / reject / 置顶 / 排序;
   AI 模型可先给出判断辅助人工。命令行交互审核 `media_server -catalog-review` 作为备用入口。
3. **上线**:分类浏览只展示 `approved` 数据;审核通过即生效。

## 收集新候选

- 用 AI 模型或人工搜集合规公开视频,按上面的导入信封格式汇集成 JSON,交给 admin 导入。
- 也可用 admin 的“实时聚合(seed)”按分类抓一批**粗略搜索结果**灌为 `pending` 候选,
  再交给人工 + AI 审核。代码侧只负责粗略搜索,不做精准的分类相关性判定。
- 失效(下架/付费/地区限制)的条目,在 admin 复核时由人工/AI 直接 reject 或删除。
