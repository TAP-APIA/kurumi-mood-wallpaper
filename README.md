# 久留美情绪壁纸

安卓动态壁纸：根据所选市场指数当日涨跌幅，自动切换久留美的 5 档循环动画（表情和天气随之变化）。

| 档位 | 涨跌幅 | 视频 |
|---|---|---|
| 5 | > +2% | assets/videos/mood_5_bigsmile.mp4（灿烂阳光、微笑） |
| 4 | +0.5% ~ +2% | assets/videos/mood_4_smile.mp4（晴天、轻笑） |
| 3 | -0.5% ~ +0.5% | assets/videos/mood_3_calm.mp4（平静） |
| 2 | -2% ~ -0.5% | assets/videos/mood_2_worried.mp4（阴天、担忧） |
| 1 | < -2% | assets/videos/mood_1_cry.mp4（下雨、流泪） |

## 跟踪指数

在 App 主界面可切换：

| 选项 | 主接口（东财 push2his `f170/100`） | 回退 |
|---|---|---|
| 上证指数 | `secid=1.000001` | 新浪 `s_sh000001` → 腾讯 `sh000001` |
| 沪深300 | `secid=1.000300` | 新浪 `s_sh000300` → 腾讯 `sh000300` |
| 纳斯达克 | `secid=100.NDX`（纳斯达克100） | 新浪 `gb_$ndx` / `int_nasdaq` → 腾讯 `usNDX` |
| 恒生指数 | `secid=100.HSI` | 新浪 `rt_hkHSI` → 腾讯 `hkHSI` |
| 黄金 (7×24 PAXG) | OKX `PAXG-USDT`（www / aws 两个域名） | Gate `PAXG_USDT` → 火币 HTX `xautusdt`（无 PAXG，用 XAUT 代替） → Bitget → KuCoin → MEXC `GOLD(PAXG)USDT` → CoinGecko |
| 自定义 API | 用户填写的 URL + JSON 路径 | — |

黄金使用 PAX Gold（链上代币化黄金，7×24 交易），周末也会变化；涨跌为近 24 小时（OKX 以北京时间 0 点开盘价为基准）。数据源按国内可达性排序。

每个数据源超时约 5 秒并重试 1 次，失败则换下一个；全部失败时「最近错误」会列出已尝试的源，并保留上一档情绪。之后任意一次成功会自动清除该行情错误（渲染错误单独保留）。主界面在涨跌幅旁显示「更新于 HH:mm」。旧版选了「黄金」的用户会自动沿用新数据源。

说明：`push2.eastmoney.com` 在部分网络返回 502，代码使用 `push2his.eastmoney.com`（带重试）。新浪请求带 `Referer: https://finance.sina.com.cn`。

### 自定义 API

1. 选择「自定义 API」。
2. 填写 URL（`http://` 或 `https://`），响应须为 JSON。
3. 填写 JSON 路径，支持点号与 `[下标]`，例如：
   - `data.f170`（东财：数值为涨跌幅×100，乘数填 `0.01`）
   - `data.items[0].pct`（已经是百分比时，乘数填 `1`）
4. 乘数：解析出的数字再乘以该值得到日涨跌幅%。
5. 点「保存自定义设置」，再用「测试自定义 API」验证；解析失败会写入「最近错误」，并保留上一档情绪。

示例（东财上证，从本机可用的 push2his）：

- URL: `https://push2his.eastmoney.com/api/qt/stock/get?secid=1.000001&fields=f170`
- 路径: `data.f170`
- 乘数: `0.01`

- 行情每 15 分钟刷新（壁纸可见时），离线保留上次档位；切换指数后立即刷新。
- 渲染：Engine 自有 EGL/GLES2 线程，MediaPlayer → SurfaceTexture(OES) → 全屏 center-crop；视频未就绪或出错时绘制 `res/drawable-nodpi/mood_N_*_still.jpg` 静帧，EGL 失败时退回 Canvas，避免黑屏。最近的渲染错误/状态会显示在 App 主界面。
- 素材：Google Flow（Nano Banana 修图 + Veo 3.1 首尾同帧循环视频），`assets/stills` 为每档首帧。
- 原图来自 X 用户 @L98808Lju 的久留美壁纸，仅供个人使用。
- App 会提示申请「忽略电池优化」，减轻系统杀后台导致壁纸停更。

## 自动更新

App 打开时（最多每 12 小时一次）请求 GitHub `releases/latest`，若 tag 版本高于当前版本，弹窗显示更新说明，可选「更新 / 稍后」。更新会下载 APK 并调起系统安装器（首次需允许「安装未知应用」）；下载失败则打开发布页。主界面有「检查更新」按钮。GitHub 在国内可能较慢，超时会静默失败。

覆盖安装要求签名一致：所有版本都用同一个 debug keystore 签名。构建时默认读取 `/workspace/kurumi/signing/kurumi-debug.keystore`（可用 `-PkurumiKeystore=` 或环境变量 `KURUMI_KEYSTORE` 指定）；keystore 不入库。

## 构建

需要 JDK 17 和 Android SDK 34：

```
./rebuild.sh
# 或:
cp assets/videos/*.mp4 app/src/main/res/raw/
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
