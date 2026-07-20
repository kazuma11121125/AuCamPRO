# 録画動画のカクつき(フレームレート崩壊)調査 — 原因①修正済み・原因②調査継続中

最終更新: 2026-07-20
コード基準: `dad2cb4` + 本セッションの作業ツリー変更(下記「変更したファイル」参照、未コミット)
対象実機: Sony SO-51C
状態: **原因①は根本原因を特定・修正・実機検証済み。原因②はAE_MODE_OFFが実機で悪化要因と
確定したが未解決。AE_ON/AE_OFF/AE_LOCKの3条件比較と、露出モード明示分離の設計まで完了、
実装はまだ行っていない。**

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
- 次に進めるのは、露出制御を**ユーザーが録画前に選ぶ明示的な2モード**へ分離する設計・
  最小実装(下記§4)。大規模な本実装はまだ行わない。

## 4. 設計案: 露出制御の明示モード分離(未実装)

### 4.1 モード定義

1. **Auto Exposure**
   - `CONTROL_AE_MODE_ON`
   - `CONTROL_AE_TARGET_FPS_RANGE`を選択中のfpsに設定(`Range(fps, fps)`)
   - `SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME`/`SENSOR_FRAME_DURATION`は
     手動設定しない(AE ONの効果を上書きしてしまうため)

2. **Manual Exposure**(既存の`applyManualExposure`相当、変更なし)
   - `CONTROL_AE_MODE_OFF`
   - ISO・露光時間・フレーム時間を手動設定
   - 露光時間はフレーム周期以下へクランプ(§2.3で実装済みの
     `clampExposureTimeNanosToFrameRate`をそのまま使う)

**モード切替は録画開始前のみ**。録画中は現在のモードを維持し、自動切替は行わない
(既存の`if (_uiState.value.isRecording) return`ガードのパターンを踏襲)。

### 4.2 変更が必要な箇所(未実装・リストのみ)

| ファイル | 変更内容 |
|---|---|
| `camera/CameraParams.kt` | `exposureMode: ExposureMode`(enum: `AUTO`/`MANUAL`、デフォルト`MANUAL`=現状維持)を追加 |
| `camera/ManualCaptureRequestFactory.kt` | 既存`applyManualExposure()`はそのまま残し、新規`applyAutoExposure(builder, fps)`を追加(AE_MODE_ON + AE_TARGET_FPS_RANGEのみ設定) |
| `camera/CameraSessionController.kt` | `buildRequestBuilder()`が`params.exposureMode`で`applyManualExposure`/`applyAutoExposure`を分岐 |
| `ui/viewmodel/CameraUiState.kt` | `exposureMode: ExposureMode = ExposureMode.MANUAL`を追加(既存`wbAuto`/`afAuto`と同じ位置づけ) |
| `ui/viewmodel/CameraControlViewModel.kt` | `setExposureMode(mode)`を追加(`setWbAuto`/`setAfAuto`と同じパターン: `_uiState.update` + `pushCameraParamsThrottled()`)。**録画中は呼んでも無視**するガードを追加(`if (_uiState.value.isRecording) return`) |
| `utils/UserPreferencesStore.kt` | `exposureMode`の永続化(既存`wbAuto`/`afAuto`と同じパターンで`KEY_EXPOSURE_MODE`) |
| UI(設定パネル) | Auto/Manual切り替えトグル追加。録画中はグレーアウト(既存のfps/解像度ピッカーが録画中にグレーアウトされているのと同じ扱い) |

### 4.3 次の実機検証候補: AE_ON + AE_LOCK

§3.2の比較にもう1条件を追加する。

3. **AE_ON + AE_LOCK**: `CONTROL_AE_MODE_ON`で露出収束後に`CONTROL_AE_LOCK=true`を
   設定するモード。露出は自動収束させつつ、収束後は値を固定する(完全なAE_OFFではない
   が、ユーザーが録画中に露出が動くのを嫌う場合の妥協案になり得る)。

3条件(AE_ON / AE_ON+AE_LOCK / AE_OFF)を、同程度の`CAM_CRITICAL`発火状況下で比較する
——この投稿時点では未実施。実機再検証時は、前回と同様
「`StreamingA FPS`(センサー)」と「`video stream FPS`(HAL配信)」の両方をログ取得し、
両者の比を3条件で並べる。

## 5. まとめ

| # | 原因 | 発生条件 | 状態 |
|---|---|---|---|
| ① | シャッタースピードが映像fpsと無関係に永続化され、`frameDuration>=exposureTime`制約で実際のfpsを食う | 常時 | **修正済み・実機検証済み**(60fps: 33fps→58fps、30fps: 目標に一致) |
| ② | AuCamPROのセッションに対してのみ、高温時にHAL内部でフレーム配信が崩れる(Sony純正は同条件で無影響)。`AE_MODE_OFF`常時強制が悪化要因と確定(3〜5fps→21〜22fpsに改善)だが未解決 | 端末が`CAM_CRITICAL`の時のみ | 未解決。露出モード明示分離(§4)が次の実装候補、AE_LOCK条件の追加検証が次の調査候補 |

## 6. このセッションで変更したファイル(原因①の修正、コミット前)

- `tools/rec_diagnose.py`(新規) — MP4カクつき/WAV音割れ診断ツール
- `app/build.gradle.kts` — `BuildConfig`にgit SHA/dirty/ビルド時刻を埋め込み
- `app/src/main/java/com/aucampro/recorder/AuCamPROApplication.kt` — 起動時に`BuildInfo`ログ出力
- `app/src/main/java/com/aucampro/recorder/camera/CaptureRangeClamper.kt` — `clampExposureTimeNanosToFrameRate()`追加
- `app/src/main/java/com/aucampro/recorder/ui/viewmodel/CameraControlViewModel.kt` — `selectVideoConfig()`/`switchLens()`/`attachPreviewSurface()`再アタッチパスにクランプ適用
- `app/src/test/java/com/aucampro/recorder/camera/CaptureRangeClamperTest.kt` — 上記のテスト追加

`camera/CameraCapabilityInspector.kt`と`pipeline/RecordingPipeline.kt`は、§2.1で反証した
仮説の実装(セッション能力チェック)を追加した後、同じセッションで削除済み——差分としては
変化なし。
