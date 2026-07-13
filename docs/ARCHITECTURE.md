# ProCamera — Phase 1: アーキテクチャ設計書

学祭ライブバンド演奏(音圧110〜125dB SPL)を Android 端末1台で長時間・高音質・高画質録画するプロフェッショナル向け録画アプリの Phase 1 成果物。命令書 `android_recording_app_prompt_v2 (1).md` §0〜§5 に基づく。

このフェーズで出力するファイル一覧:

- `settings.gradle.kts` / `build.gradle.kts` (root) / `app/build.gradle.kts`
- `gradle/libs.versions.toml`(Version Catalog)
- `gradle.properties` / `gradlew` / `gradle/wrapper/gradle-wrapper.properties`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/cpp/CMakeLists.txt` + `app/src/main/cpp/jni/native-lib.cpp`(ビルド疎通確認用スタブ。実装は Phase 2)
- `app/src/main/java/com/procamera/recorder/{ProCameraApplication,AppContainer}.kt`
- `app/src/main/java/com/procamera/recorder/ui/MainActivity.kt`(スタブ)
- `app/src/main/java/com/procamera/recorder/service/RecordingService.kt`(スタブ)
- `app/src/main/java/com/procamera/recorder/audio/NativeEngineBridge.kt`(スタブ)
- `app/src/main/res/**`(values, drawable, mipmap-anydpi-v26)
- 本ドキュメント `docs/ARCHITECTURE.md`

**実ビルド検証について**: 本プロジェクトは Claude Code(ファイルシステム直接操作可能なエージェント)が実装しているため、命令書 §0 が前提とするチャット分割出力(「次へ」で継続、トークン上限での中断・再開)ではなく、**実ファイルをリポジトリに書き出す方式**を採用する。またこの環境に JDK 21(Android Studio 同梱 JBR)・NDK r27d・Gradle 9.6.1・SDK cmake 3.31.6 を導入し、上記スタブ構成で **`./gradlew assembleDebug` / `assembleRelease` / `lint` / `testDebugUnitTest` の実ビルドに成功済み**(Kotlin/Compose コンパイル、CMake/ninja による C++20 ネイティブビルド、Oboe の prefab 経由リンクまで通過)。ただし実機・エミュレータは無いため、**録画動作そのものの実機検証は行っていない**。

---

## Phase 2: C++レイヤー全実装(Oboe Engine / DSP / Lock-Free RingBuffer / JNI)

このフェーズで出力したファイル一覧:

- `app/src/main/cpp/common/Result.h`(`std::expected`代替の自前`Result<T,E>`)
- `app/src/main/cpp/common/TripleBuffer.h`(EQ係数受け渡し用のWait-Free Triple Buffer)
- `app/src/main/cpp/common/Log.h`
- `app/src/main/cpp/buffer/SpscRingBuffer.h`(Lock-Free SPSC RingBuffer、cache-line整列・acquire/release根拠をコメント内に明記)
- `app/src/main/cpp/dsp/BiquadEq.{h,cpp}`(RBJ Cookbook 3-band Peaking EQ、係数はUIスレッド計算・Triple Buffer経由でAudioスレッドへ、コールバック内は線形補間のみでクリックノイズ対策)
- `app/src/main/cpp/dsp/SafetyLimiter.{h,cpp}`(Look-aheadなしソフトクリッパー、-1.0dBFS閾値)
- `app/src/main/cpp/dsp/PeakRmsMeter.{h,cpp}`(Peak/RMS dBFS計算、Atomic pull方式)
- `app/src/main/cpp/engine/OboeFullDuplexEngine.{h,cpp}`(Oboe Full-Duplexエンジン本体、SharingMode/InputPresetフォールバックラダー、デバイス切替、モニタリング制御)
- `app/src/main/cpp/jni/native-lib.cpp`(実JNIバインディング一式)
- `app/src/main/java/com/procamera/recorder/audio/NativeEngineBridge.kt`(実装済みKotlin側ラッパー)
- `app/src/main/cpp/test/`(ホストビルド用GTestプロジェクト一式。§4.7で要求されるBiquad/Peak-RMS/RingBufferテストをPhase2内で前倒し実装)

### ネイティブ単体テスト(ホストビルド、実機なしでの検証)

命令書§4.7はGTestをPhase5の成果物としているが、advisorレビュー(Phase1完了時)の指摘どおり実機・エミュレータの無いこの環境では「ビルドが通る」ことと「正しく動く」ことの間に大きな乖離があるため、ロックフリー系コンポーネントとDSPのGTestをPhase2実装と同時に前倒しで整備した。

実行方法:
```
cmake -S app/src/main/cpp/test -B build-host-test -DCMAKE_BUILD_TYPE=Debug
cmake --build build-host-test -j
./build-host-test/procamera_native_tests
```
ASan/UBSanを既定で有効化(`PROCAMERA_TEST_SANITIZERS=ON`)。GoogleTest 1.17.0をFetchContentで取得。24テスト全てPASS(SpscRingBuffer/TripleBufferの実マルチスレッドストレステストを含む)。

**このテストで実際にバグを1件検出・修正した**: `SafetyLimiter`のソフトクリッパーが`tanh()`のfloat32飽和により、大振幅入力で出力が厳密に1.0(0dBFS)へ到達してしまう不具合(`SafetyLimiterTest.SignalAboveThresholdIsCompressedNeverReachesFullScale`が検出)。閾値からの飽和先を1.0ではなく`0.999`(≈-0.0087dBFS)に変更して修正。「assembleDebugが通る」だけでは検出できなかった類のバグであり、GTestをPhase2に前倒しした判断の実証になった。

### Phase2で追加した判断ログ

11. **C++エラー処理の`expected`パターン**: `common/Result.h`に自前の`Result<T,E>`(`std::variant`ベース、非throwアクセサのみ使用)を実装し、`OboeFullDuplexEngine`のstream open/reopen/monitoring制御等で使用。
12. **EQ係数の受け渡し方式**: 命令書は「Atomicなダブルバッファ」と表現しているが、素朴な2スロット構成には「Writerが短時間に2回連続publishすると、Readerが読んでいる最中のスロットを上書きしてしまう」書き込み競合(tearing)のリスクが実在する(UIスライダーの高速ドラッグ等で発生しうる)。これを構造的に排除する **Wait-Free Triple Buffer**(3スロット、CAS/リトライなし)を`common/TripleBuffer.h`に実装し、これを「ダブルバッファ概念の実用的な実装」として採用した。
13. **SafetyLimiterのソフトクリップ飽和先**: 上記の通り、`tanh`のfloat32飽和により文字通り0dBFSに到達するバグをGTestで検出し、飽和先を`0.999`linear(閾値からの相対スケール)に変更して修正済み。

---

## 採用バージョン一覧(2026-07-13 時点で実在確認済み)

| 項目 | 採用値 | 確認方法 |
|---|---|---|
| compileSdk / targetSdk | 36 (Android 16) | Google Play targetSdk 要件(2026-08-31 以降 API36必須)に一致。SDK Platform `android-36.1` 導入済み |
| minSdk | 29 (Android 10) | 命令書指定どおり |
| AGP (Android Gradle Plugin) | **9.2.1** | Google Maven `maven-metadata.xml` で確認した最新安定版(9.3.0 系はまだ rc) |
| Kotlin | **2.4.0** | Maven Central で確認した最新安定版 |
| Gradle | **9.6.1** | `services.gradle.org/versions/current` で確認 |
| NDK | **27.3.13750724 (r27d, 2024 LTS)** | `android/ndk` wiki で確認。r30 はまだ beta のため現行 LTS は r27d のまま(命令書の想定どおり) |
| CMake (SDK component) | 3.31.6 | Google Maven repo XML から取得・導入 |
| C++ 標準 | C++20 | 命令書指定どおり。NDK r27d の libc++ で問題なくビルド確認済み |
| Jetpack Compose BOM | **2026.06.01** | Google Maven `compose-bom` metadata で確認 |
| androidx.core / core-ktx | **1.18.0** | 1.19.0 は `minCompileSdk=37` を要求し compileSdk=36 と衝突するため 1 段階前の 1.18.0(`minCompileSdk=36`)を採用(実ビルドの AAR metadata チェックで検出・確定) |
| Google Oboe | **1.10.0** | GitHub Releases/Tags で確認した最新タグ。Maven Central 経由の **prefab パッケージ**(`com.google.oboe:oboe:1.10.0`)として消費し、C++ 側は `find_package(oboe CONFIG)` で解決(ソース vendoring はしない) |
| DI | 手動 DI(`AppContainer`) | 後述 |

---

## DI方式の明確化

**手動 DI**(`ProCameraApplication` が保持する `AppContainer` に手で依存を積む方式)を採用する。Hilt/Dagger は導入しない。

理由: 本プロジェクトは単一 `:app` モジュールで、DI グラフの規模も中程度(Camera/Audio/Encoder/Muxer/Service 程度のシングルトン群)。すでに NDK ネイティブビルドでビルド時間の重い構成になっているところへ KSP/KAPT のアノテーション処理を追加するコストが見合わない。将来複数モジュール化や DI グラフが複雑化した場合は Hilt への移行を再検討する(移行コストは小さい規模のうちに留めておく)。

---

## スレッドモデル図

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Main Thread (UI)                                                        │
│  - Compose UI / Choreographer 60fps ポーリング(Peak/RMSメーター表示)     │
│  - CameraDevice/CaptureSession コールバック(Camera2 のデフォルト挙動)     │
│  - StateFlow/SharedFlow の collect                                       │
└───────────────┬───────────────────────────────────────────────────────--┘
                │ Coroutine (Dispatchers.Default / IO) 経由の非同期呼び出しのみ
                │ ※ Audio コールバックスレッドからの直接呼び出しは禁止(§4.2)
┌───────────────▼───────────────────────────────────────────────────────--┐
│ Kotlin Coroutine Workers (Dispatchers.Default / IO)                     │
│  - PtsClockDomain(オフセット計算・単調増加ガード)                        │
│  - SegmentManager(N分毎の Muxer 切替オーケストレーション)                │
│  - ThermalMonitor / StorageMonitor / BackpressureMonitor                │
└───────────────┬─────────────────────────────┬───────────────────────--─┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼──────────────────────--──┐
│ Video Encoder Callback Thread │  │ Audio Encoder Thread(専用 Thread)     │
│ (MediaCodec.Callback, 非同期) │  │  - RingBuffer からポーリングドレイン   │
│  - InputSurface 経由でCamera  │  │  - Float32→16bit(TPDF dither)変換     │
│    HALが直接書き込む(アプリ   │  │  - queueInputBuffer                    │
│    コードはPTS/バッファ管理の │  │                                        │
│    み)                        │  │                                        │
└───────────────┬──────────────┘  └────────────┬──────────────────────--─┘
                │  onOutputBufferAvailable       │ onOutputBufferAvailable
                └───────────────┬────────────────┘
                                ▼
                  ┌──────────────────────────┐
                  │ Muxer Writer Thread        │
                  │ (専用 Thread。両トラック   │
                  │  のFORMAT_CHANGED後start)  │
                  │  - ペンディングキュー保持  │
                  │  - セグメント境界でシーム  │
                  │    レス切替               │
                  └──────────────────────────┘

──────────────────────────── C++ / Native 側 ────────────────────────────

┌───────────────────────────────────────────────────────────────────────┐
│ Oboe Audio Callback Thread (RT優先度, Oboe/AAudioが生成・管理)          │
│  【禁止事項厳守】malloc/new/delete, Mutex, Sleep, CondVar, ブロッキング │
│  I/O, 大量ログ, JNI呼び出し 一切禁止(§4.2)                             │
│                                                                         │
│  Input stream                                                          │
│    → Biquad 3-band EQ (Atomicダブルバッファから係数読取)                │
│    → Safety Limiter (soft clip, -1.0dBFS)                              │
│    → Peak/RMS 計算 → Atomic<float> へ書込(Kotlin側がpull)              │
│    → SPSC Lock-Free RingBuffer へ push(→ Audio Encoder Thread が drain)│
│    → (ヘッドホン検出時のみ) パススルー出力ストリームへ書込               │
└───────────────────────────────────────────────────────────────────────┘
```

**設計思想**: オーディオコールバックスレッドは他のどのスレッドとも直接同期しない。Kotlin 側・Video 側との通信は SPSC Lock-Free RingBuffer と Atomic 変数のみを経由し、コールバックスレッドが「待たされる」経路を物理的に作らない。これにより爆音環境下でのバースト処理時間超過(xrun)リスクを最小化する。

---

## データフロー図

```
                         ┌───────────────────┐
                         │  Camera2 HAL       │
                         └─────────┬──────────┘
                    ┌───────────────┴────────────────┐
                    ▼                                  ▼
        ┌───────────────────────┐         ┌──────────────────────────┐
        │ Preview Surface        │         │ MediaCodec InputSurface   │
        │ (SurfaceTexture)       │         │ (createInputSurface)      │
        │  → Focus Peaking /     │         │  → SENSOR_TIMESTAMP 付き  │
        │    Digital Zoom 後処理 │         │    フレームが直接エンコーダへ│
        │  ※録画ストリームに影響 │         │  → Video Encoder Callback │
        │    を与えない独立経路  │         │    Thread                 │
        └───────────────────────┘         └────────────┬──────────────┘
                                                          │ Video PTS
                                                          ▼
  ┌──────────────┐    Float32 PCM   ┌──────────────────────┐         ┌───────────────┐
  │ Oboe Input   │ ───────────────► │ SPSC RingBuffer        │──────► │ Audio Encoder  │
  │ Stream       │  (Audio Callback │ (Lock-Free, cache-line │ drain  │ Thread          │
  │ (マイク)      │   Thread内で push)│  aligned)              │        │ (16bit+dither)  │
  └──────┬───────┘                  └────────────────────────┘        └───────┬────────┘
         │ (ヘッドホン検出時のみ)                                              │ Audio PTS
         ▼                                                                    │ (累積サンプル数/48000)
  ┌──────────────┐                                                            │
  │ Oboe Output   │                                                            │
  │ Stream        │                                                            │
  │ (モニタ出力)  │                                                            │
  └──────────────┘                                                            │
                                                                                │
         ┌──────────────────────────────────────────────────────────────────┘
         │
         ▼
  ┌─────────────────────────────┐
  │ PtsClockDomain               │  Video/Audio 両PTSを録画開始=0の同一エポックへ正規化
  │ (単調増加ガード含む)          │  UNKNOWNクロック較正はここで実施(後述)
  └───────────────┬───────────────┘
                   ▼
  ┌─────────────────────────────┐
  │ Muxer Writer Thread          │  両トラックFORMAT_CHANGED後にstart。
  │ (ペンディングキュー→          │  N分毎にセグメント切替(次Muxerを事前準備し
  │  セグメント分割)              │  キーフレーム境界でシームレス切替)
  └───────────────┬───────────────┘
                   ▼
        MediaStore (Movies/ProCamera/*.mp4, IS_PENDING運用)
```

---

## PTS同期設計(詳説)

### Video PTS

`createInputSurface()` 経由のフレームは Camera HAL が `SENSOR_TIMESTAMP` 由来の PTS を自動付与する。起動時に `SENSOR_INFO_TIMESTAMP_SOURCE` を確認し、次の2経路に分岐する。

- **`TIMESTAMP_SOURCE_REALTIME`**: 公式ドキュメント上、この場合の `SENSOR_TIMESTAMP` は `SystemClock.elapsedRealtimeNanos()`(= `CLOCK_BOOTTIME`)と同一クロックドメインであることが規定されている。Audio 側アンカー(後述、`CLOCK_MONOTONIC` 系)との差分は録画開始時に一度だけ `elapsedRealtimeNanos() - (Audio アンカー時点の同義クロック値)` で確定できる。
- **`TIMESTAMP_SOURCE_UNKNOWN`**: 下記の較正手順が必須(命令書 §4.3 で具体化を要求されている箇所)。

### `UNKNOWN` クロックソース較正アルゴリズム(具体案)

**確信度の明示**: 以下はベンダー実装のクロック特性(オフセットのみか、ドリフトも持つか)を実機なしに断定できないため、「オフセットは一定」という仮定に立った較正手順であり、ドリフトの有無自体は録画中の継続監視でしか判定できないという前提を置く。

1. **サンプリング**: 録画開始後、最初の `K = 10` フレームについて、`CameraCaptureSession.CaptureCallback#onCaptureCompleted` が呼ばれた瞬間に `System.nanoTime()`(Android では `CLOCK_MONOTONIC`)を取得し、同コールバックの `TotalCaptureResult` から同一フレームの `SENSOR_TIMESTAMP` を取得する。`onCaptureCompleted` と InputSurface へ渡る実フレームは同一キャプチャリクエストに属するため、両者は同一フレームの2つのタイムスタンプ表現とみなせる。
2. **オフセット推定**: 各サンプル `i` について `offset_i = nanoTime_i - sensorTimestamp_i` を計算し、**中央値**(外れ値耐性のため平均ではなく median を採用)を較正オフセット `offset_calibrated` として確定する。
3. **適用**: 以降到着する全フレームの Video PTS を `videoPts = sensorTimestamp + offset_calibrated` として `CLOCK_MONOTONIC` 系(Audio PTS と同じ基準)へ正規化する。
4. **較正完了までの扱い**: Muxer は両トラックの `INFO_OUTPUT_FORMAT_CHANGED` 受領後まで `start()` しない(§4.4 の既定動作)ため、較正中の最初の数フレームはこのペンディング期間内に収まる想定であり、較正未完了フレームがそのまま出力される実害は小さい。ただし理論上のトレードオフとして、K=10 フレーム分(30fps なら約 333ms)は較正確定前のため、この間に取得したフレームの PTS 精度はやや低い。
5. **継続監視**: 較正はオフセットの一点推定に過ぎず、ドリフト(較正後に both クロックの進み方が乖離するケース)を検出できない。そのため §4.3 で規定される定期的な `AudioStream::getTimestamp()` によるオーディオ HW クロックと monotonic クロックの乖離監視と同様に、以後も定期的に `onCaptureCompleted` タイミングでオフセットを再サンプリングし、`offset_calibrated` からの乖離が閾値(暫定 15ms、Audio 側と同一閾値)を超えたら警告ログ+UI表示に留める(自動補正はしない。理由: サンプリング自体に数msのスケジューリング揺らぎが避けられず、揺らぎに対して自動再較正をかけるとPTSが逆に不安定化するリスクがあるため)。

### Audio PTS

録画開始時、最初に取得したオーディオバーストに対して `CLOCK_MONOTONIC` 上のアンカー時刻を記録。以降は壁時計に依存せず「累積サンプル数 ÷ 48000」で厳密算出する(ドリフトフリー)。

### クロックドメイン統合とPTS単調性

`PtsClockDomain` クラスが Video/Audio 双方のPTSを録画開始=0のエポックへ正規化する。Muxer 手前で「直前に書き込んだPTS以下の値が来た場合は破棄しログを残す」単調増加ガードを実装し、クロックの逆行・重複によるMuxer破損を防ぐ。

---

## クラス構成(パッケージ構成)

```
com.procamera.recorder
├── ProCameraApplication / AppContainer      … 手動DIコンポジションルート
├── camera/
│   ├── CameraCapabilityInspector             … HW Level/4K対応/ISO・露出範囲等の実機検証ロジック
│   ├── Camera2Controller                     … CaptureSession/Request管理、マニュアル露出/AF/WB
│   ├── FocusController                       … Touch-to-Focusハイブリッド機構(§4.1)
│   └── ColorTemperatureConverter              … Kelvin→RGGBゲイン変換
├── audio/
│   ├── NativeEngineBridge                     … JNIブリッジ(C++ Oboeエンジンへの唯一の入口)
│   ├── AudioDeviceRouter                      … registerAudioDeviceCallback監視・優先順位ルーティング
│   └── AudioMeterPoller                       … Choreographerでのpull方式ポーリング(§4.5)
├── encoder/
│   ├── VideoEncoder                           … MediaCodec非同期モード、InputSurface
│   └── AudioEncoder                           … RingBufferドレイン専用スレッド
├── muxer/
│   ├── SegmentedMuxerController                … N分毎シームレス分割(§4.4)
│   └── PtsClockDomain                          … PTS正規化・単調増加ガード・UNKNOWN較正
├── service/
│   └── RecordingService                        … Foreground Service(camera|microphone)
├── ui/
│   ├── MainActivity / ProCameraRoot(Compose)
│   └── (CameraControlsPanel, AudioControlsPanel, RecordingStatusBar 等 Phase4で追加)
└── utils/
    ├── ThermalManager                          … API29/30分岐(§4.6)
    ├── CrashHandler
    └── Logger

cpp/
├── engine/   … OboeFullDuplexEngine(Full-Duplex構成)
├── dsp/      … BiquadEq(RBJ Cookbook)、SafetyLimiter、PeakRmsMeter
├── buffer/   … SpscRingBuffer(cache-line整列、Acquire/Release memory ordering)
└── jni/      … JNIバインディング(唯一Kotlin側と接する層)
```

---

## ディレクトリ構造

実際にリポジトリへ作成済み(詳細は各ファイル参照):

```
ProCamera/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── gradlew
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/procamera/recorder/{camera,audio,encoder,muxer,service,ui,utils}/
│       │   ├── res/{values,drawable,mipmap-anydpi-v26}/
│       │   └── cpp/{engine,dsp,buffer,jni}/ + CMakeLists.txt
│       ├── test/java/com/procamera/recorder/        … JUnit (Phase5)
│       └── androidTest/java/com/procamera/recorder/ … Instrumented tests (Phase5)
├── docs/ARCHITECTURE.md  … 本ファイル
└── .github/workflows/    … Phase5で追加
```

---

## 前提・判断ログ

命令書の記述だけでは一意に決まらず、実装者(Claude)が判断を下した箇所。後続フェーズはこのログと矛盾しないよう実装する。

1. **アプリ名 / パッケージ名**: 命令書に指定がないため、リポジトリ名 `ProCamera` に合わせ、アプリ名 "ProCamera"、`applicationId`/`namespace` = `com.procamera.recorder` とした。
2. **AGP 9 のビルトインKotlin**: 実ビルド検証で判明した重要事実として、AGP 9.0 以降は Kotlin サポートがビルトイン化され、`org.jetbrains.kotlin.android` プラグインを併用するとビルドエラーになる(`kotlin-compose` プラグインのみ明示適用すればよい)。Version Catalog・root/app の `build.gradle.kts` はこれに合わせて構成済み。命令書は「Kotlin 2.x」を実装時点の最新版と確認のうえ採用するよう指示しているため、この対応はその指示の範囲内の判断とみなす。
3. **`kotlinOptions`/`compilerOptions` DSL**: AGP 9 + KGP 2.4.0 のこの組み合わせでは `android { compilerOptions {} }`(android ブロック内)は解決しなかったが、**`android {}` と同階層のトップレベル `kotlin { compilerOptions {} }` ブロックは解決し、実ビルドで機能を確認済み**。`app/build.gradle.kts` でこの形式により `jvmTarget = JvmTarget.JVM_17` を明示している。Compose/Coroutines の実験的API opt-in 等で `freeCompilerArgs` が必要になった場合もこのブロックに追加する。
4. **androidx.core-ktx バージョン**: 1.19.0 は `minCompileSdk=37` を要求し、compileSdk=36(命令書§1.1が明示指定する値)と衝突したため、1.18.0(`minCompileSdk=36`)を採用し、**compileSdk=36 という命令書の明示値はそのまま維持**した(SDK Platform 37自体はこの環境で利用可能であることは確認済みで、37へ上げる選択肢もあるが、命令書の具体的な数値指定からの逸脱になるため独断では行わない)。今後 Phase2〜4 で追加する依存が同様の `minCompileSdk=37` 要求を出した場合の**事前承認済みフォールバック**: targetSdk は36のまま(Play ポリシー準拠に影響しない)、compileSdkのみ37へ引き上げる、または該当ライブラリを36互換バージョンへピン留めする、のいずれかを都度選ぶ。
5. **Oboe の導入方法**: ソースを vendoring せず、Maven の **prefab パッケージ** (`com.google.oboe:oboe:1.10.0` + `android.buildFeatures.prefab = true` + CMake `find_package(oboe CONFIG)`)として消費する。ソース vendoring より依存管理・CI再現性の面で優れ、Oboe 公式が推奨する現行の配布形態であるため。
6. **ABI フィルタとx86_64の扱い**: 命令書 §4.8「CI上のホストGTest実行やエミュレータ検証で必要な場合のみx86_64を補助的に含める」を、**出荷用(release/debug通常ビルド)のABIはarm64-v8aのみとし、x86_64はPhase5のCI設定内でエミュレータ実行時のみオーバーライドして含める**、と解釈した。`app/build.gradle.kts` の `defaultConfig.ndk.abiFilters` は arm64-v8a 固定。
7. **PCMフォーマット(Float32→16bit)**: MediaCodecの標準AAC-LCエンコーダ(`OMX.google.aac.encoder`/`c2.android.aac.encoder`)はPCM_16BIT入力を前提とすることが広く確認できる一方、PCM_FLOAT入力の全FULL級端末での動作は実機検証なしに断定できない。安全側に倒し、**Oboeから得たFloat32をエンコーダ手前でTPDFディザ付き16bit整数へ変換する**方針とする(実装はPhase2/3)。
8. **画面回転の扱い**: 120分の連続録画中にActivity/Camera2セッションが再生成されるとPTS連続性が壊れるリスクが大きいため、`android:screenOrientation="locked"` でActivity自体の回転再生成を止め、UI要素(アイコン等)の回転表示のみ別途 `OrientationEventListener` 等で対応する設計とする(Phase4で実装)。命令書の「画面回転を含むライフサイクル管理」は、録画継続性を最優先する前提のもとこの意味で満たす。
9. **セグメント分割デフォルト長**: 命令書の「デフォルト5分、設定可」をそのまま採用。
10. **UNKNOWNクロック較正のサンプル数K**: 上記の通りK=10フレームを暫定値として採用(実機での揺らぎ計測結果次第でPhase3実装時に調整の余地を残す)。
11. **C++エラー処理の`expected`パターン**: `app/build.gradle.kts`は`-fno-exceptions`を指定しており、かつC++標準はC++20のため`std::expected`(C++23で追加)は利用できない。命令書§2「C++はエラーコード/`expected`パターン」を満たすため、Phase2で軽量な自前の`Result<T, E>`型(`cpp/`共通ヘッダに配置)を実装し、Oboe呼び出し結果やRingBuffer操作結果等に用いる。
12. **ネイティブ単体テストの前倒し**: 命令書は Google Test を Phase5 の成果物としているが、実機・エミュレータが無いこの環境では **ホストビルドのGTest が唯一の実行可能な正しさの検証手段**(especiallyロックフリーRingBufferのマルチスレッドストレステスト、PTS単調増加ガード、Biquad周波数特性)である。そのためPhase2の実装と同時にGTestのホストビルド環境を先行して整備し、各コンポーネント実装直後に対応するテストを書いて実行する(Phase5では追加テストとCI組み込みのみを行う)。

**矛盾・実現不可能な要求の有無**: 命令書自体が主要な既知の落とし穴(minSdk29と`getThermalHeadroom`のAPIレベル不整合、Float/16bit変換精度、UNKNOWNクロック較正の要求)を事前に自己解決する形で書かれており、Phase1着手時点で追加の承認が必要な技術的矛盾・実現不可能な要求は見つからなかった。上記1〜10は「粒度不足を埋めた判断」であり、矛盾の指摘とは別categoryである。

**Phase2着手時に発見した矛盾(ユーザー承認済み)**: 命令書§0のフェーズ分割は PTS管理・Kelvin→RGGB変換を Phase3(Kotlinレイヤー)の範囲としているが、§4.7 はこれらを **Google Test(C++専用)** で検証するよう指定しており、実装言語の想定が矛盾していた。ユーザーに確認し、**PTS計算(単調増加ガード・UNKNOWNクロック較正含む)・Kelvin→RGGB変換は両方ともKotlinに実装し、JUnitで検証する**(§4.7の当該箇所はGTestではなくJUnit対象と読み替える)ことで解決した。Phase1のクラス構成(`PtsClockDomain`は`muxer/`、`ColorTemperatureConverter`は`camera/`、いずれもKotlin)は変更不要。
