# 性能・安定性 調査報告 (2026-07-17)

実機 SO-51C (Xperia 1 IV, Android 14) 上で AuCamPRO / Sony Video Pro / Sony Photo Pro を
同条件(プレビューアイドル・静止シーン・40秒区間)で計測し、残る CPU 差の内訳を確定した。
さらに実機に残っていたクラッシュ痕跡から **未修正のネイティブクラッシュ(use-after-free)を1件特定**した。
本書は調査のみ。実装は別途。

---

## 1. 結論サマリ

1. **アイドルCPUの Sony 比 3 倍(54% vs 18%)のうち、17pt はdebugビルド税、残り約19pt はほぼ全てUIの常時再描画。**
   releaseビルド(AOT/speed)で 54%→**37%** に低下。残差 37% vs 18% の内訳は
   メインスレッド+RenderThread = 22.7% vs Sony 約3% で、**アイドル中に31fpsでUIを描画し続けている**ことが原因
   (gfxinfo 実測: 30秒で935フレーム、Sony 両アプリの RenderThread はほぼ 0)。
2. **音声パイプラインは release では Sony と同等**(AudioRecordスレッド 1.7% vs Video Pro 2.3%)。
   これ以上削る必要なし。
3. **実クラッシュを1件特定**: 7/16 23:20 の tombstone。`AudioEncoder` ドレインスレッドが
   `SpscRingBuffer::read` 中に SIGSEGV。`AudioEncoder.stop()` の 3 秒 join タイムアウト後に
   `NativeEngineBridge.close()` がネイティブエンジンを破棄する **use-after-free** で、コード上で連鎖を完全に裏取り済み。
   「時より出る不具合」の最有力候補。負荷(熱)で発生率が上がる特性も既知の状況証拠と一致する。

---

## 2. 計測結果

### 2.1 プロセス全体 (1コア換算 CPU%, プレビューアイドル 40 秒)

| | AuCamPRO debug | AuCamPRO **release+AOT** | Video Pro | Photo Pro |
|---|---|---|---|---|
| **合計** | 54% | **37%** | **18%** | **14%** |
| メインスレッド | 21% | 11.1% | 2.7% | 1.9% |
| RenderThread | 10.9% | **11.7%** | **0.4%** | **0.02%** |
| 音声キャプチャ | 5.3% | **1.7%** | 2.3% | — |
| カメラCB+binder群 | ~12% | ~9% | ~9% | ~10% |
| HistogramReader | 1.7% | 1.2% | — | — |
| **TOTAL PSS** | 132MB | 107MB | 78MB | 69MB |

- 計測方法: `/proc/<pid>/stat` の utime+stime 差分(スクリプトは §6)。
- カメラコールバック+binder 群(TotalCaptureResult の毎フレーム配送)は **Sony もほぼ同額を払っており削減対象ではない**。

### 2.2 UI描画レート (dumpsys gfxinfo)

| | フレーム数/30秒 | 実効fps | jank |
|---|---|---|---|
| AuCamPRO(アイドル・静止シーン) | 935 | **31fps** | 2.6% |
| Sony 両アプリ | RenderThread がほぼ 0 tick | **~0fps** | — |

**静止シーン・無音でも 31fps で再描画し続けている**。Sony は「表示値が変わった時だけ」描画している
(Video Pro には SpiritLevelMoni スレッドが存在=センサーは監視しつつ、RenderThread は 16 tick/40秒しか使っていない)。

### 2.3 何が31fpsを駆動しているか(コード裏取り済み)

| 発生源 | 頻度 | メカニズム |
|---|---|---|
| 音声メーター | 20Hz | `CameraControlViewModel.startMeterPolling()` (`METER_POLL_INTERVAL_MS=50`) が毎tick `_meterState.update{copy(...)}`。peakDb/rmsDb は**生float**なので無音でもノイズフロアが毎回微変動=毎tick必ず新値→`AudioMeterHost` 再コンポーズ+再描画 |
| 水準器 | ~16Hz | `LevelGaugeOverlay.kt:167` センサーイベント毎に `rollDegrees` state 書き込み。静止していてもセンサーノイズで毎回値が変わる。さらに `:183 val isLevel = abs(rollDegrees)<…` が**コンポジションスコープで読んでいる**ため draw 限定でなく毎tickフル再コンポーズ |
| ヒストグラム | 5Hz | `LuminanceHistogramReader` は throttle 済みだが、**毎回新しい FloatArray を publish**(`_histogramBins.value = bins`)するため静止シーンでも 5Hz で再コンポーズ+再描画 |

Compose の再コンポーズ隔離(Host分離・Canvas化・Text排除)は前回対策で既に正しく実施済み。
**今回の問題は「再コンポーズが重い」ではなく「invalidationの頻度そのもの」**。simpleperf でも
メインスレッド上位は `CompositionImpl.addPendingInvalidationsLocked` / `AudioMeterHost` 等の invalidation 機構だった。

---

## 3. 安定性: 特定したクラッシュ

### 3.1 【S1・最優先】AudioEncoder ドレインスレッドの use-after-free (実クラッシュ)

**証拠**: 実機 dropbox `2026-07-16 23:20:03 data_app_native_crash`
```
pid 22413, tid 25265, name: AudioEncoderDra  >>> com.aucampro.recorder <<<
signal 11 (SIGSEGV), SEGV_MAPERR, fault addr 0x23cc00
  #00 __memcpy_aarch64_simd
  #01 aucampro::SpscRingBuffer<float>::read(float*, unsigned long)+320
  #02 aucampro::OboeFullDuplexEngine::drainEncoderBuffer+44
  #03 Java_..._nativeDrainEncoderBuffer
  #13 com.aucampro.recorder.encoder.AudioEncoder.drainLoop$drainOneBlock
(Foreground: No, Process uptime: 426s)
```

**連鎖(コードで裏取り済み)**:
1. バックグラウンド遷移等で `ViewModel.onCleared()` → `RecordingPipeline.stopAll()`。
2. `stopRecordingInternalLocked` → `AudioEncoder.stop()` (`AudioEncoder.kt:146`) は `running` を倒して
   `join(3000ms)`。**タイムアウトすると警告ログだけ残してリターンする**(設計意図は codec の
   stop/release 競合回避で、それ自体は正しい)。
3. しかしドレインスレッドは `running=false` 後も**最終ドレインループ**(`AudioEncoder.kt:215
   while(drainOneBlock())`)で `nativeEngine.drainEncoderBuffer()` を呼び続ける。
4. 呼び出し元は続けて `stopAll()` → `nativeEngine.close()` (`NativeEngineBridge.kt:90`) →
   `nativeDestroy(handle)` がエンジンごと `SpscRingBuffer` を解放。
5. 生存中のドレインスレッドが解放済みバッファを read → SIGSEGV。

**補強事実**:
- `NativeEngineBridge.drainEncoderBuffer` (`NativeEngineBridge.kt:84`) は `peakDb`/`rmsDb` と違い
  **`closed` ガードすら無い**(あっても TOCTOU なので根治ではない)。
- 高負荷(熱・GC・muxerロック競合)ほど最終ドレインが 3 秒を超えやすい —
  「GAIN クラッシュは熱で出る」というこれまでの観測と整合。encoder_error.txt 系の過去障害と同族の可能性が高い。

**修正方針(推奨順)**:
1. **ネイティブ側でハンドルをロック保護**: `native-lib.cpp` のハンドル解決を
   shared_mutex(呼び出し=shared, destroy=exclusive)にする。destroy は進行中の JNI 呼び出しの
   完了を待ち、以後の呼び出しは no-op を返す。TOCTOU 根治・呼び出し側の順序に依存しない。
2. 併せて Kotlin 側の防御: `drainEncoderBuffer`/`insertSilence` 等にも `closed` チェックを追加し、
   `stopAll()` では `nativeEngine.close()` の前にドレインスレッドの終了を(無制限 join で)待つ。
   ※無制限 join は onCleared 上なので、1 の native ガードを本命とし、こちらは順序の整流化として実施。

### 3.2 【S2・記録】カメラHALプロセスのwatchdog abort

`2026-07-17 00:26` に `vendor.somc.hardware.camera.provider@1.0-service` 自体の SIGABRT
(watchdog)が実機に残っていた。うちのアプリ起因と断定できる証拠は無いが、HAL watchdog は
アプリ側のバッファ返却停滞で誘発されることがある。再発時はタイムスタンプをうちのログと突合すること。

### 3.3 【S3・軽微】AWB/AF 自動値の uiState churn

`CameraControlViewModel.kt:117-136`: wbAuto/afAuto(共にデフォルトtrue)中、3Hz で測定値が届き、
kelvin ±50K / focus ±0.1 を超えると `_uiState.update` → **画面全体スコープの再コンポーズ**。
表示は "AWB" 固定で kelvin は画面に出ていないのに、である。光源が揺れる環境では常時 churn する。
→ スライダー表示中のみ購読する別 StateFlow に分離するか、コントロール非表示中は publish しない。

---

## 4. 優先度付き修正リスト

| # | 対象 | 修正 | 期待効果 |
|---|---|---|---|
| **P1** | S1 クラッシュ | native ハンドルの shared_mutex ガード + close 前のドレイン終了待ち | 再現性のある実クラッシュの根治 |
| **P2** | メーター20Hz | publish 前に量子化(peak/rms を 0.5dB 刻み等)+ `update{}` 内で**値が同じならスキップ**(StateFlow は equals で配信抑制されるので data class の値が変わらなければ再コンポーズ自体が消える) | 無音/静音時の 20Hz 描画→ほぼ 0。**メイン+Render の大半を削る本命** |
| **P3** | 水準器16Hz | ① roll を 0.1° に量子化してから state 書き込み(静止時は値が変わらず invalidation 消滅) ② `isLevel`/`lineColor` の算出を draw ラムダ内へ移動(コンポジションスコープでの state 読み排除) | 静止時 16Hz 再コンポーズ→0 |
| **P4** | ヒストグラム5Hz | bins の前回値との差分(例: L1ノルム)が閾値未満なら publish しない | 静止シーン 5Hz 再描画→0 |
| **P5** | リリース構成 | `isMinifyEnabled=true`(R8)を有効化して検証(JNI クラスは default rules で保持されるが要実機確認)。**今後の性能評価・体感評価は必ず release+AOT で行う**(debug は+17pt のART/CheckJNI税がある) | 起動・PSS・Compose 実行効率の改善 |
| P6 | S3 churn | AWB/AF 測定値を uiState から分離 | 揺れる光源下のメイン負荷減 |
| P7 | AudioEncoder | `BUFFER_EMPTY_SLEEP_MS=2` → 5ms(1ブロック=10.6ms なので依然余裕2倍) | 録画中の wakeup 500/s→200/s(録画時の電力) |
| P8 | リポジトリ掃除 | ルートの `java_pid126529.hprof`(300MB, Gradle OOM ダンプ)削除 | ディスク |

**P2-P4 実施後の見込み**: メイン+Render 22.7% → Sony 並みの 3-5% 圏 = **プロセス合計 ~37% → 約 17-20%(Video Pro 同等)**。

### 見送り(調査済み・対応不要)

- **音声DSPチェーン**: InputGain/MakeupGain/HPF は bypass 実装済み。EQ デフォルト曲線
  (Low−6dB/Mid+3dB/High−4dB, 仕様§4.2)が常時走るのは製品仕様であり、release 実測で
  音声系は Sony 同等のためコスト問題なし。
- **カメラ CaptureCallback / binder 配送 (~9%)**: Sony も同額。TotalCaptureResult の毎フレーム
  配送は tap-to-focus 応答性と AWB/AF 表示に必要。
- **PSS 107MB vs 78MB**: 差は主に Compose ランタイム+ヒストグラム/フォト用 ImageReader。
  R8(P5)で数十MB は縮む見込み。危険水準ではない。

---

## 5. 検証手順(修正後に同条件で再計測)

```bash
# release ビルド→インストール→AOT
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.aucampro.recorder
adb shell am force-stop com.aucampro.recorder
adb shell am start -n com.aucampro.recorder/.ui.MainActivity

# CPU計測 (デバイスに /data/local/tmp/measure2.sh 配置済み)
adb shell "sleep 18 && sh /data/local/tmp/measure2.sh com.aucampro.recorder 40"

# UI描画レート: 静止シーンで 31fps → ほぼ 0fps になっていることを確認
adb shell dumpsys gfxinfo com.aucampro.recorder reset
sleep 30
adb shell dumpsys gfxinfo com.aucampro.recorder | grep "Total frames"

# クラッシュ再発監視
adb shell dumpsys dropbox | grep -i crash
```

合格基準: プレビューアイドル(静止・静音)で **合計 ≤20%/1コア、UI 0-2fps**、
メーター/水準器/ヒストグラムは入力変化時に従来通り追従すること。

---

## 6. 計測環境メモ

- 実機: SO-51C (Xperia 1 IV), Android 14, docomo 64.2.C.2.256。電源接続・画面常時ON・熱ステータス NONE。
- 比較対象: Video Pro (`jp.co.sony.mc.videopro`) / Photo Pro (`com.sonymobile.photopro`) 各プレビューアイドル。
- CPU: `/proc/<pid>/stat` utime+stime の 40 秒差分(スレッド別は `/proc/<pid>/task/*/stat`)。
  スクリプト: `/data/local/tmp/measure2.sh`(このリポジトリ外・セッションの scratchpad 由来)。
- プロファイル: `simpleperf record --app com.aucampro.recorder -f 500 --duration 20`。
  debug ビルドには CheckJNI (`ScopedCheck`)・インタープリタ実行 (`artQuickToInterpreterBridge`) が
  明確に乗っていた=**debug 計測は Sony 比較に使えない**(本書 §2 は release 値を正とする)。
