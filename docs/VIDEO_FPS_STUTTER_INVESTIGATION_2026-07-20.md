# 録画動画のカクつき(フレームレート崩壊)調査 — 原因①修正済み・原因②調査継続中

最終更新: 2026-07-20
コード基準: `f19893a`(原因①修正+診断ツール) + branch `feat/exposure-mode-comparison`(露出モード分離)
対象実機: Sony SO-51C
状態: **原因①は根本原因を特定・修正・実機検証済み(main向けコミット済み)。原因②は
`AE_MODE_OFF`常時強制が悪化要因と確定(3-5fps→21-22fpsに改善)したが未解決。
ユーザー承認の製品方針に基づき、Auto/Manual露出モードの明示分離を実装・実機検証済み
(branch `feat/exposure-mode-comparison`、Draft PR作成済み、mainへ未マージ)。実機で
静止画撮影がAE_MODE_ON関連で2段階のカメラクライアント停止を起こすことを発見し、
最終的にAutoモード中の静止画撮影自体を無効化して解決(§4.3)。AE_LOCK比較の自動化・
専用UI/データモデルは意図的に未実装(§4.4)。**

## 0. 経緯・使ったツール

`tools/rec_diagnose.py`(このセッションで新規作成)で、保存済みMP4のフレーム間隔とWAVの
音割れ/レベルを解析できるようにした。

```
python3 tools/rec_diagnose.py 録画.mp4 録画.wav --out out_dir
```

- MP4: `ffprobe -show_entries packet=pts_time` でフレームの実タイムスタンプを取得し、
  期待間隔(コンテナの`r_frame_rate`基準)との比で「孤立したカクつき」と「移動平均で見る
  持続的な速度低下」を分けて検出する。
- WAV: このアプリの`WavFileWriter`が書く32-bit float RIFF/RF64形式を含め、連続クリップ
  検出とdBFSレベルメーターを出す。

いずれも合成データ(単発カクつき/持続的な半速区間/RF64ファイル/クリップがブロック境界を
またぐケース)で検証済み。長時間hi-res録音でもメモリを一定量に抑えるようブロック処理して
いる。今後もMP4/WAVのカクつき・音割れ調査全般に再利用できる汎用ツール。

## 1. 症状

2026-07-20に同日連続で撮影した5本(10:33〜11:48)のMP4で、程度の差はあるが全て
カクついていた。宣言fps(30/33/60)と実測が一致しない・後半ほど悪化する、という
パターンが目立った。

| ファイル | 宣言fps | カクつき率 | 失われた時間 | 特徴 |
|---|---|---|---|---|
| 103307_339 | 30fps | 1.6% | 4.9秒 | 軽微 |
| 110753_598 | 60fps | 100% | 150秒/249秒 | 全編、実測平均24fps。最初から破綻 |
| 112435_691 | 33fps | 5.0% | 22.5秒 | 中盤〜終盤に遅延領域 |
| 113437_567 | 33fps | 20.1% | 62.6秒 | 後半ほど悪化(前半13.9%→後半26.4%) |
| 114426_690 | 33fps | 6.2% | 26.2秒 | 中程度、後半やや悪化 |

いずれも音声(192kHz WAVサイドカー)を同時録音していた。**当初は2つの別原因が混在した
症状だと判明**(以下①②)。

## 2. 原因①(常時発生・確定・修正済み): シャッタースピードがfpsと無関係に永続化される設計

### 2.1 最初に立てて反証した仮説

`CameraCapabilityInspector.isVideoConfigSupported()`は`MediaCodecList`(エンコーダ
チップの能力)しか見ておらず、`CameraCharacteristics`(センサー/ISPが実際にそのfpsを
出せるか)を一切チェックしていなかった。「これが原因では」と考え、
`StreamConfigurationMap.getOutputMinFrameDuration()`でカメラセッション側の実際の
上限を見るチェックを実装・実機テストした。

**この仮説は実機データで反証した**: この端末の`StreamConfigurationMap`は1080p/720pの
60fpsを`getOutputMinFrameDuration=16.67ms`(=60fps可能)と自己申告しており、実装した
チェックは何もフィルタしなかった(fail-open)。プラットフォームAPI自体は誤っていない
——後述の通り、実際の制約は別のところにあった。**この`isFrameRateSustainableBySession`
チェックはコードから削除した**(死んだ確認ロジックを残すと将来の誤った安心材料になる
ため)。

### 2.2 確定した本当の原因

`CaptureResult`の実測値(要求値ではなく結果)を実機ログで確認:

```
resultExposureTimeNanos=29,921,000ns  (≈1/33.4秒)
resultFrameDurationNanos=29,987,114ns (≈1/33.3秒、露光時間とほぼ同じ)
```

Camera2は常に`SENSOR_FRAME_DURATION >= SENSOR_EXPOSURE_TIME`を要求する。60fps
(16.67ms周期)を選んでも、**シャッタースピードが1/33s前後で残っていたため、
フレーム周期がその露光時間に引き上げられていた**。シャッタースピードは映像解像度/fpsの
選択とは完全に独立したUI設定で、`UserPreferencesStore`に永続化され次回起動時に
復元される——「今日録った5本がバラバラのfps設定(30/33/60)なのに実測が軒並み
30〜33fpsに揃っていた」のは、この永続化されたシャッタースピードが常に同じ値だった
ため。

### 2.3 修正

`CaptureRangeClamper`(companion object)に純粋関数を追加:

```kotlin
fun clampExposureTimeNanosToFrameRate(exposureTimeNanos: Long, frameRate: Int): Long {
    val frameDurationNanos = 1_000_000_000L / frameRate
    return minOf(exposureTimeNanos, frameDurationNanos)
}
```

`CameraControlViewModel`の、映像fpsが変わる3箇所すべてでこれを適用(露出時間を新しい
fpsの周期以下へクランプし、クランプでフィットしなくなった`shutterPreset`は解除):

- `selectVideoConfig()`(ユーザーが手動で解像度/fpsを選択)
- `switchLens()`(レンズ切替時、`initialVideoConfig`復元パス)
- `attachPreviewSurface()`内の再アタッチパス(ギャラリー復帰などでのセッション再構成)

### 2.4 実機検証結果

| 項目 | 修正前 | 修正後 |
|---|---:|---:|
| 1080p60選択時のセンサー実測(`StreamingA FPS`) | ~33.3fps | ~58.2fps |
| 1080p30選択時のセンサー実測(冷えている時) | ~33fps付近に収束 | 29.9〜30.0fps(目標にほぼ一致) |

60fpsが実際に60fps近くまで出るようになった。**この修正は原因①のみを解決するもので、
原因②(後述、高温時のみ発生)には無関係。**

## 3. 原因②(端末が高温の時のみ発生・未解決): カメラHAL内部でのフレーム損失

端末を意図的に冷やさず高温状態のまま同条件で再テストしたところ、原因①修正後でも
なお深刻な劣化が発生した。

- 実測: 効fps 6〜11fps台、最大1.83〜5.87秒のフリーズ
- `libthermal_engine`ログで実際の熱保護発火(`CAM_CRITICAL`/`CAM_LTB_TIM_SET=10`)を
  複数回確認
- `StreamingA FPS`(センサー実測)は**この間も健全**(29.6〜33fps付近を維持)。しかし
  `video stream FPS`/`preview stream FPS`(カメラHALがアプリのSurfaceへ配信する
  レート)はセンサーと無関係に大きく崩れていた

```
センサー(StreamingA)      健全(目標fps付近)
        ↓
HAL内部の配信(video/preview stream)  ← ここで崩壊
        ↓
AuCamPROのエンコーダ/アプリ側          (届いていないので手を出せない)
```

### 3.1 「HAL全体の熱保護」説は反証済み

同じ`CAM_CRITICAL`状態のままSony Video Proで録画したところ、`StreamingA FPS`が
ほぼ完全に60fpsを維持し、全く崩れなかった。`CAM_CRITICAL`自体は端末全体のカメラ供給を
絞る仕組みではなく、**AuCamPROが組んでいるセッション/リクエストの立て方に対してだけ、
HALが供給を落としている**ことを確定。

### 3.2 AE_MODE_OFF常時強制の寄与 — 確定(ただし完全な説明ではない)

`ManualCaptureRequestFactory.applyManualExposure()`は、マニュアル露出モードの
有無に関わらず毎リクエストで無条件に`CONTROL_AE_MODE_OFF`を設定している。これが
高温時のHAL内部フレーム損失にどう影響するか、実機で切り分けた。

同程度の`CAM_CRITICAL`発火状況下(同日、連続撮影による同程度の高温)での比較:

| 条件 | センサー実測(`StreamingA`) | HAL配信実測(`video stream`) | 目標30fpsとの比 |
|---|---:|---:|---:|
| **AE_MODE_OFF**(既存本番コード) | 約29.6fps(健全) | **3〜5fps** | 約85〜90%のフレーム損失 |
| **AE_MODE_ON** + `CONTROL_AE_TARGET_FPS_RANGE(30,30)`(実機スロー実験、コミットせず) | 約29.97fps(健全) | **21〜22fps** | 約27〜30%のフレーム損失(約4倍改善) |

**結論**:
- `CONTROL_AE_MODE_OFF`の常時強制が、高温時のHAL内部フレーム損失を大幅に悪化させて
  いることは確定した。
- ただしAE_MODE_ONでも目標30fpsには届いておらず(21〜22fps)、**完全な解決ではない**。
  高温そのものによる追加の制約、Surface構成、Sony HAL固有のセッション経路など、
  他の要因が残っている可能性が高く、未解決。

### 3.3 製品方針(ユーザー決定、2026-07-20)

- **常時フルマニュアル露出という設計方針自体を廃止し、全面的にAE_MODE_ONへ移行する
  ことはしない。** マニュアル露出(ISO/シャッタースピードの手動制御)はこのアプリの
  中核機能であり、既存の`Manual Exposure`パスは削除しない。
- **録画中にAE_OFF↔ONを無断で自動切り替えする実装もしない。**
- 露出制御を**ユーザーが録画前に選ぶ明示的な2モード**へ分離する(下記§4、実装済み)。

## 4. 露出モードの明示分離(実装・実機検証済み)

### 4.1 モード定義

1. **Auto Exposure**(`ExposureMode.AUTO`)
   - `CONTROL_AE_MODE_ON`
   - `CONTROL_AE_TARGET_FPS_RANGE`——`Range(fps, fps)`を無条件には使わず、
     `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`から実際に選べる範囲を選ぶ
     (`CaptureRangeClamper.selectAeFpsRange`、優先順位: ①`[fps,fps]`固定レンジ
     ②fpsを含む最も狭いレンジ③同じ幅なら下限が高い方④該当なしなら`Range(fps,fps)`に
     フォールバック)
   - `SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME`/`SENSOR_FRAME_DURATION`は
     手動設定しない(AE ONの効果を上書きしてしまうため)

2. **Manual Exposure**(`ExposureMode.MANUAL`、デフォルト=既存動作)
   - `CONTROL_AE_MODE_OFF`
   - ISO・露光時間・フレーム時間を手動設定
   - 露光時間はフレーム周期以下へクランプ(§2.3の`clampExposureTimeNanosToFrameRate`を
     ViewModel側のfps変更時とCaptureRequest生成直前の両方で適用——二重防御)

**モード切替は録画開始前のみ**。`CameraControlViewModel.setExposureMode()`は
`isRecording`中は無視する(`selectVideoConfig`と同じガードパターン)。UIのSwitchも
録画中は`enabled=false`。

### 4.2 実装したファイル

| ファイル | 変更内容 |
|---|---|
| `camera/ExposureMode.kt`(新規) | `enum class ExposureMode { AUTO, MANUAL }` |
| `camera/CameraParams.kt` | `exposureMode: ExposureMode = ExposureMode.MANUAL`、`debugAeLock: Boolean = false`(§4.4参照)を追加 |
| `camera/CaptureRangeClamper.kt` | `selectAeFpsRange()`追加(純粋関数、`android.util.Range`ではなく`Pair<Int,Int>`で表現——このクラスの既存方針と同じ理由でホストテスト可能にするため) |
| `camera/ManualCaptureRequestFactory.kt` | 新規`applyAutoExposureForVideo(builder, targetFps)`追加(動画/プレビュー専用)。既存`applyManualExposure()`に二重防御クランプを追加。fpsレンジが見つからない場合は`CONTROL_AE_TARGET_FPS_RANGE`自体を設定しない(未公開の`Range(fps,fps)`を送らない) |
| `camera/CameraSessionController.kt` | `buildRequestBuilder()`が`params.exposureMode`で`applyAutoExposureForVideo`/`applyManualExposure`を分岐。**`capturePhoto()`(`TEMPLATE_STILL_CAPTURE`)は分岐せず常に`applyManualExposure`固定**——理由は§4.3の実機不具合参照 |
| `ui/viewmodel/CameraUiState.kt` | `exposureMode: ExposureMode = ExposureMode.MANUAL`を追加。**`canCapturePhoto`が`exposureMode == MANUAL`も要求するよう変更**(§4.3) |
| `ui/viewmodel/CameraControlViewModel.kt` | `setExposureMode(mode)`追加(録画中ガード付き)。`toCameraParams()`/`restorePersistedSettings()`/`PersistSnapshot`に反映。**`capturePhoto()`に`canCapturePhoto`の実効ガードを追加**(§4.3) |
| `utils/UserPreferencesStore.kt` | `exposureMode`永続化(`KEY_EXPOSURE_MODE`、文字列名。旧バージョン/不正値は常に`MANUAL`へフォールバック) |
| `ui/MainScreen.kt` | ISO/SHUTTERスライダーの上に「EXPOSURE: AUTO/MANUAL」Switch追加。Autoの間はISO/SHUTTER/シャッタープリセットを無効化(値は保持、消去しない)。写真シャッターボタンもAuto中はグレーアウト |
| `ui/components/ManualControlSlider.kt` | `IsoSlider`/`ShutterSlider`に`enabled`パラメータ追加 |

`CameraParams.debugAeLock`(AE_LOCK比較実験用フラグ)は当初追加したが、後述の理由で
削除した——製品データモデルに常時`false`のデバッグ専用フィールドを残さない判断。

### 4.3 実機で発見・修正した不具合: Autoモード中の静止画撮影でカメラクライアントが停止(2段階)

**1回目**: `capturePhoto()`(`TEMPLATE_STILL_CAPTURE`)にも動画と同じ
`applyAutoExposure(builder, targetFps)`(`AE_MODE_ON` + `CONTROL_AE_TARGET_FPS_RANGE`)を
適用したところ、Autoモードで静止画を撮った瞬間にプレビュー全体が復帰不能になるまで
固まった。ログ上は`Camera2ClientBase`のデストラクタが実行され、カメラクライアントごと
終了していた(アプリ自体はクラッシュせずプロセスは生存、`mResumed=true`のまま)。

**2回目**: `CONTROL_AE_TARGET_FPS_RANGE`が原因という仮説のもと、静止画専用に
`CONTROL_AE_TARGET_FPS_RANGE`を設定しない`applyAutoExposureForStill()`
(`AE_MODE_ON`のみ)を用意して差し替えたが、**それでも実機で再現した**。今回はログに
`Camera3-Device: reconfigureCamera: Can't idle device in 5.000000 seconds!`——写真を
2枚撮った数秒後にHAL側のidle待ちがタイムアウトし、その後カメラクライアントが強制切断
された。1回目と合わせると、**`CONTROL_AE_TARGET_FPS_RANGE`の有無に関係なく、
`CONTROL_AE_MODE_ON`自体を`TEMPLATE_STILL_CAPTURE`(単発リクエスト)に使うことが、
このSony HALでは不安定である**ことが確定した(`TEMPLATE_RECORD`のリピートリクエストで
は同じ`AE_MODE_ON`が問題なく動作している——単発リクエストへの適用だけが問題)。

**最終対応(ユーザー承認済みの回避策)**: 静止画側にAuto用の適用関数は用意しない
(`applyAutoExposureForStill`は削除)。代わりに、**Autoモード中は静止画撮影そのものを
無効化する**——`CameraUiState.canCapturePhoto`が`isPreviewing && exposureMode ==
MANUAL`を要求するよう変更し、`CameraControlViewModel.capturePhoto()`にも同じ条件の
実効ガードを追加した。**この実効ガードが必要だった理由**: オンスクリーンの📷ボタンは
Composeの`pointerInput`で`canCapturePhoto`を見て無効化されるが、**ハードウェアの
カメラキー**(`MainActivity.dispatchKeyEvent` → `onShutterPressed()`)はそのUIの
`pointerInput`ゲートを経由しない別経路のため、ViewModel層に実際のガードがないと
素通りしてしまう(実機で確認: ハードウェアキーでの連続撮影がまさにこの経路で発生した)。

実機検証済み(2026-07-20、再起動後含む): Autoモードで📷ボタン/ハードウェアキーとも
無反応(クラッシュ・フリーズなし)、MANUALへ切替後は撮影正常、レンズ切替後・録画停止
直後・ギャラリー復帰後のいずれもAutoモードでは無反応のまま安定。

Autoモード中に静止画を撮りたい場合、`AE_MODE_ON`を単発リクエストに使う以外の方法
(例: 直前のリピートリクエストのAE収束値をそのまま`AE_MODE_OFF`+手動値としてコピーする
など)を実機で個別に検証する必要がある——次にこの経路を触る時は必ず実機で確認すること。

### 4.4 未実装(意図的にスコープ外): AE_LOCK比較の自動化・専用UI

当初案(ChatGPT提供の仕様)では、`AUTO_LOCK_TEST`という第3のデバッグ専用モードを
Developer Options的なUIで切り替え、`CONTROL_AE_STATE`の収束をCaptureResultで監視して
タイムアウト付きで自動的に`CONTROL_AE_LOCK=true`にする、という専用ハーネスを想定していた。

**今回はこれを実装していない**。当初`CameraParams.debugAeLock: Boolean`と
`applyAutoExposure(..., lock: Boolean)`パラメータを用意したが、§4.3の2回目の実機不具合
(静止画Auto全面禁止)を受けて**削除した**——AE_LOCKの有効性自体が未検証な状態で、
常に`false`かつ製品UIから到達不能なフィールドを本番の`CameraParams`データモデルに
恒久的に残すのは避ける判断(常時`false`の実験用フィールドをモデルに残さない)。

実機比較する場合は、本セッションで実際に行った方法(§3.2/§4.3の実験と同じ)——
`applyManualExposure`または`applyAutoExposureForVideo`の呼び出しを一時的にコード変更→
`./gradlew assembleDebug`→`adb install -r`→実機で同条件下でテスト→ログ確認→元に戻す
——を使うのが、まだ検証されていない機能のための恒久的なUI/データモデルを先に作るより
合理的。AE_LOCKを試す場合は`applyAutoExposureForVideo`に`lock: Boolean`引数を一時的に
追加し、`CONTROL_AE_LOCK`を設定する行を加えて実機検証してから、正式に必要か判断する。

## 5. まとめ

| # | 原因 | 発生条件 | 状態 |
|---|---|---|---|
| ① | シャッタースピードが映像fpsと無関係に永続化され、`frameDuration>=exposureTime`制約で実際のfpsを食う | 常時 | **修正済み・実機検証済み**(60fps: 33fps→58fps、30fps: 目標に一致) |
| ② | AuCamPROのセッションに対してのみ、高温時にHAL内部でフレーム配信が崩れる(Sony純正は同条件で無影響)。`AE_MODE_OFF`常時強制が悪化要因と確定(3〜5fps→21〜22fpsに改善)だが未解決 | 端末が`CAM_CRITICAL`の時のみ | 未解決。露出モード明示分離(§4)は実装・実機検証済み(録画/プレビュー用)。静止画Autoは実機不具合により無効化。AE_LOCK条件の追加検証(§4.4)は次回 |

## 6. このセッションで変更したファイル

**原因①の修正 + 診断ツール**(コミット`f19893a`、main向け):
- `tools/rec_diagnose.py`(新規) — MP4カクつき/WAV音割れ診断ツール
- `app/build.gradle.kts` — `BuildConfig`にgit SHA/dirty/ビルド時刻を埋め込み
- `app/src/main/java/com/aucampro/recorder/AuCamPROApplication.kt` — 起動時に`BuildInfo`ログ出力
- `app/src/main/java/com/aucampro/recorder/camera/CaptureRangeClamper.kt` — `clampExposureTimeNanosToFrameRate()`追加
- `app/src/main/java/com/aucampro/recorder/ui/viewmodel/CameraControlViewModel.kt` — `selectVideoConfig()`/`switchLens()`/`attachPreviewSurface()`再アタッチパスにクランプ適用
- `app/src/test/java/com/aucampro/recorder/camera/CaptureRangeClamperTest.kt` — 上記のテスト追加

**露出モード明示分離**(§4、branch `feat/exposure-mode-comparison`、mainへ未マージ・Draft PR):
- `app/src/main/java/com/aucampro/recorder/camera/ExposureMode.kt`(新規)
- `app/src/main/java/com/aucampro/recorder/camera/CameraParams.kt`
- `app/src/main/java/com/aucampro/recorder/camera/CaptureRangeClamper.kt`(`selectAeFpsRange`追加)
- `app/src/main/java/com/aucampro/recorder/camera/ManualCaptureRequestFactory.kt`(`applyAutoExposureForVideo`のみ、静止画用関数はなし)
- `app/src/main/java/com/aucampro/recorder/camera/CameraSessionController.kt`
- `app/src/main/java/com/aucampro/recorder/ui/viewmodel/CameraUiState.kt`(`canCapturePhoto`にAuto中の写真無効化を追加)
- `app/src/main/java/com/aucampro/recorder/ui/viewmodel/CameraControlViewModel.kt`(`capturePhoto()`に実効ガード追加——ハードウェアキー経路はUIの`pointerInput`ゲートを経由しないため必須)
- `app/src/main/java/com/aucampro/recorder/utils/UserPreferencesStore.kt`
- `app/src/main/java/com/aucampro/recorder/ui/MainScreen.kt`
- `app/src/main/java/com/aucampro/recorder/ui/components/ManualControlSlider.kt`
- `app/src/test/java/com/aucampro/recorder/camera/CaptureRangeClamperTest.kt`(`selectAeFpsRange`のテスト追加)

**マージ前に確認済み**: `./gradlew testDebugUnitTest`/`assembleDebug`は成功。
`lintDebug`は4件のエラーで失敗するが、いずれもこのブランチの変更と無関係な既存の問題
(`CameraControlViewModel.kt:383`の`MissingPermission`、`MainActivity.kt`の
`RestrictedApi`×2件——このブランチの変更を全て一時的にstashしても同じ4件が再現する
ことを確認済み)。実機(Sony SO-51C、再起動を挟んで2回)で、Autoモード中の写真撮影が
オンスクリーンボタン・ハードウェアキーの両方で安全に無反応になること、MANUALモードの
写真撮影・録画・レンズ切替・ギャラリー復帰のいずれも正常に動作することを確認済み。
