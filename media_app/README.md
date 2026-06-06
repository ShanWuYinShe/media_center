# Android TV App

使用 Android Studio 自带工具链编译：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

默认服务端地址是 `http://10.0.2.2:8787`，适合 Android Emulator。真机或电视盒子请使用：

```bash
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=http://服务端IP:8787
```

首页会从服务端读取内容分类，默认展示服务端返回的第一个分类。内置一级专区包括电影、电视剧、纪录片、红色经典和 Dota 专区。App 只消费服务端返回的统一视频 ID、`videoKind=vod` 和点播播放地址，不处理直播、视频来源、网页播放或外部打开。
