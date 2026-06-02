# Media Server

合规公开视频聚合服务。默认只接入 Internet Archive 中较明确的公开视频集合，并且只返回 Android TV 可直接播放的 MP4/WebM 流。

## API

- `GET /health`
- `GET /api/categories`
- `GET /api/search?q=关键词&page=1&pageSize=20`
- `GET /api/search?category=documentaries&page=1&pageSize=20`
- `GET /api/videos/:provider/:id`
- `GET /api/videos/:provider/:id/streams`

后续扩展其他来源时，请通过官方 API 或已授权的数据源实现 `VideoProvider`，不要接入盗版站点、绕过 DRM 或盗链资源。

内置一级专区为电影、电视剧、纪录片和 Dota 专区。Dota 分类会优先搜索 `match/replay/tournament/full game`，并排除 `guide/tutorial/news/highlights/cosplay` 等周边词。

如需启用 YouTube 搜索来源，请配置官方 API key：

```bash
YOUTUBE_API_KEY=你的Key npm start
```

YouTube 官方 API 只用于搜索和详情，不返回可供本地播放器直连的媒体流；客户端会提供“打开来源”入口。
