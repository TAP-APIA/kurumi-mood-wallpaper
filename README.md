# 久留美情绪壁纸

安卓动态壁纸：根据上证指数当日涨跌幅，自动切换久留美的 5 档循环动画（表情和天气随之变化）。

| 档位 | 涨跌幅 | 视频 |
|---|---|---|
| 5 | > +2% | assets/videos/mood_5_bigsmile.mp4（灿烂阳光、微笑） |
| 4 | +0.5% ~ +2% | assets/videos/mood_4_smile.mp4（晴天、轻笑） |
| 3 | -0.5% ~ +0.5% | assets/videos/mood_3_calm.mp4（平静） |
| 2 | -2% ~ -0.5% | assets/videos/mood_2_worried.mp4（阴天、担忧） |
| 1 | < -2% | assets/videos/mood_1_cry.mp4（下雨、流泪） |

- 行情：东方财富 push2 接口（f170），失败时回退新浪 `s_sh000001`；每 15 分钟刷新，离线保留上次档位。
- 素材：Google Flow（Nano Banana 修图 + Veo 3.1 首尾同帧循环视频），`assets/stills` 为每档首帧。
- 原图来自 X 用户 @L98808Lju 的久留美壁纸，仅供个人使用。

## 构建
需要 JDK 17 和 Android SDK 34：

```
cp assets/videos/*.mp4 app/src/main/res/raw/
./gradlew :app:assembleDebug
```
