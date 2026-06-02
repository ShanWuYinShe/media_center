# Media Center

一个合规公开视频媒体中心原型，包含：

- `media_server`：Node.js/TypeScript 服务端，聚合 Internet Archive 公共视频内容，提供搜索、详情和可播放流 API。
- `media_app`：Android TV 客户端，使用 Compose 大屏 UI 和 Media3 播放器，针对遥控器焦点、电视安全边距和 2 米观看距离优化。

## 运行服务端

```bash
cd media_server
npm install
npm run build
npm start
```

默认监听 `http://localhost:8787`。

服务端内置一级专区为电影、电视剧、纪录片和 Dota 专区。具体搜索词会覆盖长征、抗日战争、解放战争、开国元勋、毛泽东、城市家庭剧、乡村剧，以及 Dota1/Dota2 对局。Dota 分类会优先搜索对局、录像、比赛、完整局等关键词，并排除教学、新闻、集锦、娱乐周边等词。

可配置 `YOUTUBE_API_KEY` 启用 YouTube 官方搜索来源。YouTube 结果不做直链解析，客户端会通过“打开来源”交给 YouTube 客户端或网页播放；Internet Archive 等提供直连 MP4/WebM 的来源仍可在本地播放器播放。

## 编译 Android TV 客户端

项目已配置 Gradle Wrapper。建议使用 Android Studio 自带 JBR 和 Android Studio 管理的 SDK：

```bash
cd media_app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

如需连接真机或局域网服务端，可在编译时覆盖：

```bash
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=http://服务端IP:8787
```

## 合规边界

默认只搜索 Internet Archive 中较明确的公共视频集合，并只返回 MP4/WebM 直播放流。扩展其他 Provider 时应使用官方 API 或已授权数据源，不接入盗版站点、DRM 绕过或盗链资源。
