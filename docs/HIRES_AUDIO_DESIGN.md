# ハイレゾ録音 (Hi-Res Audio Recording) — 設計書

最終更新: 2026-07-18。対象コミット: `b47e20b`。

**実装状況(2026-07-18, Sonnet)**: Phase 1(96/192kHz WAVサイドカー)実装済み・
コンパイル/ホストテスト確認済み。**実機での動作確認は未実施**(セッション中に実機が
利用できなかったため)。実装は本設計にほぼ忠実だが、以下の1点だけ意図的に逸脱している:

- **§4/§6.1 の Decimator は C++(cpp/dsp/Decimator.{h,cpp} + host GTest)ではなく
  Kotlin(`encoder/Decimator.kt` + JUnit `DecimatorTest.kt`)で実装した。** 理由:
  デシメーションは RT のオーディオコールバックスレッドではなく `AudioEncoder` の
  非RTドレインスレッド(`PcmDither` と同じ場所)で動くため、そこで完結する処理を
  ネイティブに置く必然性がなく、Kotlin实装の方がこのセッション内で数値検証まで
  含めて安全に仕上げられた。4次(2段カスケード)ではエイリアス抑圧が約-22.5dBと
  弱かったため8次(4段カスケード, 標準Butterworth Q値)に強化済み(DecimatorTest参照)。
- 事前調査として実機(SO-51C)で96kHz/192kHzのネイティブOboe入力ストリームが
  MMAP(LowLatency)/レガシー両経路で実際に開けることを確認済み(本文書には反映
  していないが、実装のGoサインとして使用)。ただし**真にハードウェアが24kHz超を
  収録しているか(超音波トーン等での検証)は未確認**——§8 R2/R3は依然オープン。

実装チェックリスト(§6)は全項目着手済み。次のセッションでやるべきこと:
実機ビルド→インストール→ハイレゾ録音→WAV/MP4双方をffprobeで実測確認、
Settings sheetでの品質切替のUI動作確認、xRun/オーバーラン発生有無の確認(§8 R5)。

この文書は、AuCamPRO (`com.aucampro.recorder`) に「ハイレゾ録音」を追加するための**アーキテクチャ確定版**である。
後続の Sonnet コーディングセッションが、他の文脈なしにこの文書だけから実装できることを目標に書いている。
各設計上の問いに対して「選択肢の羅列」ではなく**1つの結論とその根拠**を書く。実機検証なしには断定できない
Android API 挙動は、末尾の「未確定リスク」に明示的に切り出してある。

> **前提(ユーザー明言)**: この機能ではファイルサイズ/ストレージ消費は**設計上の考慮対象外**。
> 出力を小さくするための設計努力(可逆圧縮の採用など)はしない。ただし WAV コンテナの 4GB 上限は
> サイズ最適化ではなく**正しさの制約**なので別途扱う(§6.4)。

---

## 0. 現状の要約(実装済みパイプライン)

音声は end-to-end で **32-bit float** として扱われている。唯一 16-bit に落ちるのは AAC エンコード直前だけ。

```
Oboe input stream (float, 48000Hz, 2ch, PerformanceMode::None)
  → onAudioReady() [RT thread]: InputGain → HighPass → 3-Band EQ → MakeupGain → SafetyLimiter → PeakRmsMeter
  → SpscRingBuffer<float> (interleaved stereo, 容量 = kSampleRate*10 frames ≈ 10秒)
  → [AudioEncoder drain thread, non-RT]: drainEncoderBuffer(float) → PcmDither(float→int16) → MediaCodec AAC-LC
  → SegmentedMuxerController (MediaMuxer, MP4) : video track と同一ファイルに mux
```

- サンプルレート/チャンネル数はネイティブ `OboeFullDuplexEngine.h` の `static constexpr kSampleRate = 48000` /
  `kChannelCount = 2`、および Kotlin `RecordingPipeline` の `AUDIO_SAMPLE_RATE_HZ`/`AUDIO_CHANNEL_COUNT`/
  `AUDIO_BITRATE_BPS` にハードコード。両者は「一致させること」とコメントされている。
- Audio PTS はサンプル数基準(ドリフトフリー)。`PtsClockDomain` は既に **`sampleRateHz` 引数で完全に
  パラメータ化**されている。Video PTS は `CLOCK_MONOTONIC` 素通しで音声と同一エポック。±20ms 同期は
  48kHz で実機実測済み(`docs/ARCHITECTURE.md` §Phase4)。
- DSP クラス (`HighPassFilter`, `ThreeBandEq`, `PeakRmsMeter`) は**コンストラクタで `sampleRateHz` を受け取る**
  ように既に書かれている。ただし `kRampSamples = 240`(HighPassFilter.h / BiquadEq.h)だけは「48kHz で ~5ms」を
  前提にしたリテラル定数(§6.5 の棚卸し参照)。
- リングバッファは **SPSC**(単一プロデューサ=RT スレッド、単一コンシューマ=AudioEncoder drain スレッド)。
  **これは不変条件であり、2つ目の読み手を足してはならない**(§4)。

---

## 1. Q1 — コンテナ/フォーマット: **別ファイルの WAV サイドカー(dual-record)**

### 結論
ハイレゾ音声は、既存 MP4 とは**別の `.wav` ファイル**として同時記録する(プロ機材の "dual-system sound" /
セーフティトラックと同じ発想)。既存の MP4(48kHz/16-bit AAC muxed track)は**一切変えず**、リファレンス
(映像と完全同期した確認用)として残す。ハイレゾ WAV は純粋な**追加シンク**であり、既存の
PTS/mux/AAC パイプラインには触れない。

### 根拠(自分で検証した内容)
- **MP4 に生 PCM は非現実的**: `MediaMuxer`(=`SegmentedMuxerController` が使う)は MP4 出力で
  `MediaFormat.MIMETYPE_AUDIO_RAW` トラックの `addTrack` を確実にはサポートしない。MediaMuxer が MP4 で
  受け付ける音声 MIME は実質 AAC (`audio/mp4a-latm`) と AMR 系のみで、生 PCM(`lpcm`/`sowt`/`ipcm`)トラックは
  API/機種横断で再生互換が保証されない。→ **未確定リスク R1**(下記)に切り出したが、いずれにせよ**この設計は
  MP4 生 PCM を使わない**ので load-bearing ではない。
- **AAC は原理的にハイレゾに不適**: AAC-LC は不可逆。さらに Android プラットフォームの AAC エンコーダの
  対応サンプルレート上限は機種依存で、多くの端末で 48kHz 止まり(`MediaCodecInfo.CodecCapabilities.
  getAudioCapabilities().getSupportedSampleRates()` で要実測)。ハイレゾの価値(高レート+高ビット深度+可逆)を
  AAC で満たすことはできない。
- **WAV(非圧縮)が最も堅牢**: ユーザーがファイルサイズを非考慮と明言している以上、非圧縮 WAV は
  「実装が最小・全プレイヤー/DAW で開ける・可逆」の三拍子。FLAC(可逆圧縮)は MediaMuxer が `.flac` コンテナを
  出力できず、自前コンテナ実装が必要になるため MVP では採らない(§7 Phase 3 候補)。
- **既存アーキテクチャの再利用**: WAV は MP4 のセグメント分割・クラッシュセーフ・保存先ロジックにそのまま
  相乗りできる(§6.4)。

### 音源のタップ位置(明示)
WAV は **DSP チェーン通過後(post-InputGain/HPF/EQ/MakeupGain/SafetyLimiter)** の float を記録する。
理由: このアプリは常時オンの `SafetyLimiter`(-1.0dBFS)を含む「録って出し」志向で、EQ/ゲインもユーザーが
現場で追い込む前提。リングバッファに入っている float が既にその処理後の信号なので、**追加処理ゼロで
そのまま WAV に書ける**(§4 参照)。DSP 前の生録(pre-DSP raw)を望むプロ需要はあり得るが Phase 3 の
オプション扱いとする(§7)。

---

## 2. Q2 — サンプルレート/ビット深度マトリクス: **シンプルな 3 段トグル、ビット深度は 32-bit float 固定(MVP)**

UI に露出する選択肢(`SettingsBottomSheet` の新セクション「音声品質」ラジオ):

| 表示 | 実体 | キャプチャ | MP4 音声トラック | WAV サイドカー |
|---|---|---|---|---|
| 標準 (デフォルト) | Standard | 48kHz/float(現状) | 48kHz/16bit AAC 256kbps | なし |
| ハイレゾ 96kHz | HiRes96 | 96kHz/float(不可なら48k) | 48kHz/16bit AAC(現状のまま) | 96kHz/32bit float |
| ハイレゾ 192kHz (Phase 2) | HiRes192 | 192kHz/float(不可なら96k→48k) | 48kHz/16bit AAC(現状のまま) | 192kHz/32bit float |

### 根拠
- **NxM グリッド(48/96/192 × 16/24/float)は出さない**。このコードベースの美意識(「Standard / Hi-Res」的な
  単純トグル、`InputKind` の 4 択など)に合わせ、レート主導の 3 択に絞る。ビット深度の選択は Phase 3 の
  拡張(§7)。
- **ビット深度は 32-bit float(WAVE_FORMAT_IEEE_FLOAT, fmt タグ=3)を採用**。理由: リングバッファが既に float
  なので **ヘッダ + memcpy だけで書ける(実装最小)**。SafetyLimiter のヘッドルームや微小信号を再量子化せず
  完全可逆。Reaper/Audition/Audacity 等プロ系 DAW は 32f WAV をネイティブに読む。
  - トレードオフ: 一部の民生プレイヤーは 32f WAV より 24-bit 整数 PCM を好む。「ハイレゾ=24bit」という
    民生ブランディングを重視するなら 24-bit を選ぶ余地はあるが、24-bit は float→int24 パッキング + ディザ
    (`PcmDither` の 24bit 版)という**追加 DSP**を要する。MVP は実装最小・完全可逆を優先し 32f。24-bit は
    Phase 3 のフォーマットピッカーで追加(§7)。ブランディング上は「96kHz/32bit Float」と表示すればよい。
- **チャンネルは 2ch(ステレオ)固定**。内蔵マイクが low-latency 経路で R チャンネル無音になる既知の
  ハードウェア問題(`OboeFullDuplexEngine.cpp` のコメント参照、`PerformanceMode::None` で回避済み)は
  ハイレゾでも変わらない。チャンネル構成には手を入れない。
- **実機での達成可能性**: 96/192kHz を**ネイティブに**出せるのは外付け USB オーディオインターフェース系が
  中心で、内蔵マイクはほぼ 48kHz 上限。→ だからこそ**能力検知とフォールバック**(§3)が本機能の要になる。
  「選べるが、実際に出せる時だけ出す。出せなければ 48k に落として明示する」という設計。

---

## 3. Q3 — 能力検知とフォールバック: **Oboe のネイティブレート交渉 + 既存フォールバック哲学の踏襲**

### 結論
録画開始時ではなく**オーディオエンジン start/reopen 時**に、要求ハイレゾレートで Oboe ストリームを開く。
このとき **サンプルレート変換を禁止**し、開いた後に `getSampleRate()` を実レートと突き合わせる。要求レートで
開けなければ**次の候補レート(192→96→48)に落とし、最終的に 48kHz は必ず成功する**。フォールバックが起きたら
UI に**実際に確定したレートを明示**する(黙って偽装しない/黙って録画失敗させない)。

### なぜこの方式か(自分で確認した既存資産)
- `AudioManager`/`AudioDeviceInfo.getSampleRates()` は多くの機種で空配列(=「不問」)を返すため、
  入力デバイスの最大レートの事前判定には**信頼できない**。
- 一方、`OboeFullDuplexEngine::openInputStreamLocked()` には既に**開いた後に実 config を検証して不一致なら
  閉じてエラーを返すガード**がある(`getChannelCount()/getSampleRate() != 期待値` → `stream->close()` → Err)。
  これはまさにハイレゾ検知に必要な仕組み。**ただし現状は `setSampleRateConversionQuality(Medium)` +
  `setFormatConversionAllowed(true)` になっており、48k デバイスに 96k を要求すると Oboe が 48→96 に
  アップサンプルして "96k で開けた" と見せかけてしまう(偽ハイレゾ)**。
  → **ハイレゾ経路では入力ストリームのサンプルレート変換を無効化する**(`setSampleRateConversionQuality(None)`
    かつレート変換を許可しない)。チャンネル変換は従来どおり許可(モノ→ステレオ救済のため)。こうすると
    ネイティブに 96k を出せない端末では open が失敗 or 別レートが granted され、既存の post-open ガードが
    検知して次候補に落ちる。
- **フォールバック哲学の一貫性**: `AudioDeviceRouter` のクラス doc が確立した「動いている内蔵マイクを黙って
  regress させない」原則、および `RecordingPipeline.ensureAudioEngineStarted()` の「候補を優先順に試して失敗したら
  次へ、最後は OS 任せ」ループと同じ構造。ハイレゾは**レートの候補ラダー**を1段追加するだけ。

### 具体的フォールバックの挙動
- ハイレゾ選択中でも、**録画開始は絶対に失敗させない**(録り逃しが最悪の結果というアプリの主目的)。
- 内蔵マイクしか無い / USB が 48k 止まり → **黙って 48k で録る**が、WAV サイドカーは 48k/32f で出す
  (ハイレゾ選択時は「レート не達でも WAV は出す」。レートは落ちてもビット深度/可逆性の価値は残る)。
  - 代替案: 「48k に落ちるなら WAV サイドカー自体を出さない」も一貫性としてあり得るが、
    **出す方を推奨**。ユーザーがハイレゾを選んだ意図(=可逆マスタートラックが欲しい)はレートが
    落ちても部分的に満たせるため。
- 実確定レートを **AUDIO パネルに表示**する。既存の `onAudioInputDeviceChanged`(実際に開いたデバイス名を
  UI に流す仕組み)と**同じパターン**で `onAudioFormatChanged(sampleRateHz, bitDepthLabel)` コールバックを新設し、
  `CameraUiState.audioFormatLabel`(例: 「96kHz/32bit Float」「48kHz(ハイレゾ非対応デバイス)」)を出す。

---

## 4. Q4 — DSP チェーン / PTS / mux 数式への影響

### 大原則: MP4/AAC/PTS/mux 経路は 48kHz のまま一切触らない
ハイレゾ WAV は**追加シンク**であり、以下は**変更しない**:
- `PtsClockDomain`(Audio PTS 数式)
- `SegmentedMuxerController` / `MuxerSampleRouter` / `SegmentRotationPlanner`(mux/回転)
- AAC エンコード(MediaCodec, 256kbps, 48kHz)
- Video PTS / A/V 同期(±20ms 実測は 48kHz で有効なまま)

### WAV シンクは AudioEncoder drain スレッドから給電する(SPSC 不変条件の厳守)
**`SpscRingBuffer` は単一コンシューマ。WAV 用に2つ目の読み手(別スレッド/別 reader)を足してはならない。**
既存の `AudioEncoder` drain スレッドが 1 ブロック読むたびに、その同じ float ブロックを

1. **そのまま WAV writer に書く**(ハイレゾ経路。無変換の float32)、かつ
2. **AAC 経路のために 48kHz へデシメーション**してから既存の `PcmDither`→AAC に渡す

という**ファンアウト**にする。読み手はあくまで1つ(drain スレッド)。これで SPSC を壊さない。
→ 実装上、WAV writer と decimator は `AudioEncoder`(または新設 `HiResAudioSink` を `AudioEncoder` が保持)に
   同居させ、drain ループの中で駆動する。

### 96kHz キャプチャ時の AAC 経路: **アンチエイリアス・デシメーションで 48k に落とす**
ハイレゾ時、Oboe/リングバッファ/DSP は 96kHz(または 192kHz)で回る。だが MP4 の AAC トラックは
**実績のある 48kHz のまま**に保つ。そのため drain スレッドで 96→48(2:1)/192→48(4:1)の**整数比
デシメーション**を行う。

- **単純な間引きは不可**。折り返し防止のローパス FIR + 間引きの**アンチエイリアス・デシメータ**を新設し、
  **host GTest で数値検証**する(既存 DSP と同じ検証流儀)。整数比なので実装は素直。
- **この設計を「タダ」とは言わない(重要)**: AAC の `cumulativeSampleCount` と PTS は 48kHz、リングバッファ/
  drain は 96kHz という**二重レート**になる。`seedAudioAnchor()` は要注意ポイント:
  - `nativeEngine.getInputTimestamp()` が返す `framePosition` は**エンジンレート(96k)フレーム**。
  - `startAudioAnchorFromFrameCorrelation(framePosition, timeNanos, engineRateHz)` に渡す**レート引数は
    エンジンレート(96k)のまま**でよい(sample0 の壁時計時刻はレート非依存に一致するため、
    anchorNanos = timeNanos − framePosition/engineRate で正しくエンジンフレーム0=デシメート後フレーム0 の
    時刻になる)。
  - **ただし `cumulativeSampleCount` の初期値だけは `framePosition / decimationFactor`(=48k フレーム換算)に
    する**。以降のカウントは 48k フレーム単位(デシメート後の出力フレーム数)。
  - これは `docs/ARCHITECTURE.md` で何度も苦しんだ `seedAudioAnchor`/frame-correlation の系譜そのものなので、
    **実装時に doc コメントで二重レート換算を明記し、host GTest で anchor 換算を固定化する**こと。
- **代替案として検討したが不採用**: 「AAC もエンジンレート(96k)で回す」案は PtsClockDomain が既にレート
  パラメータ化済みなので単一レートで数式が素直になる利点がある。しかし (a) 端末の AAC エンコーダが 96k を
  出せる保証がない(`getSupportedSampleRates()` 依存)、(b) 96k AAC は一部プレイヤーで再生不能になり得る、
  (c) ±20ms 同期の実測が 48k でしか取れていない、の3点から、**MP4 は 48k AAC 固定を維持し、デシメーションで
  橋渡しする**方を推奨する。二重レートのコストを負う代わりに、実績ある MP4 経路をバイト単位で保存する。

### ネイティブ側の「48k キーの棚卸し」(§6.5 に完全版)
`kSampleRate` を runtime 化するにあたり、48k に暗黙依存している箇所を全部潰す:
- `kSampleRate` / `kChannelCount`: `constexpr` → 実行時メンバ `sampleRateHz_`(start/reopen で確定)。
- リングバッファ容量 `kSampleRate*10`: **最大対応レート(192000*10 frames * 2ch float ≈ 15MB)で確保**するのが
  最も単純(サイズ非考慮なので許容)。あるいはレート変更時に再構築。前者推奨。
- `kRampSamples = 240`(HPF/EQ): 「48k で 5ms」のリテラル。`round(sampleRateHz_ * 0.005)` へ変更(96k で 480,
  192k で 960)。放置しても 96k で ~2.5ms とクリック防止としては許容範囲だが、正しさのため式化する。
- **モニタリング出力ストリームのレート(見落とし注意)**: `setMonitoringEnabled()` は出力ストリームを
  `setSampleRate(kSampleRate)` で開き、`onAudioReady()` が `output->write(samples, numFrames)` で**エンジンレートの
  フレーム**を書く。エンジンが 96k・モニタ出力が 48k だと**パススルーが約2倍速/ピッチ上がりで再生される**。
  → モニタ出力もエンジンレートで開く(`setSampleRate(sampleRateHz_)`)。runtime 化で必ず一緒に直すこと。
- `PeakRmsMeter`: 係数はコンストラクタで `sampleRateHz` から算出済み。runtime レートを渡せば OK。
- `AudioEncoder.FRAMES_PER_BLOCK = 512` / `BUFFER_EMPTY_SLEEP_MS = 5`: 512 フレームは 48k で ~10.7ms だが
  **96k では ~5.3ms**。「5ms ポーリングで 2倍以上の余裕」という既存コメントの前提が**半分になる**。
  → ハイレゾ時は `FRAMES_PER_BLOCK` をレートに比例させる(96k で 1024, 192k で 2048)か、`BUFFER_EMPTY_SLEEP_MS` を
    下げる。リングバッファのオーバーラン(=音声欠落)を避けるための調整。
- **DSP チェーンはレート変更時に「再構築」が必要**: `eq_`/`highPassFilter_`/`meter_` は
  コンストラクタでレートを焼き込む。レート変更はストリーム再オープンだけでなく**これらの再生成**を伴う。

### insertSilence のギャップフレーム数
`RecordingPipeline.onAudioDeviceSetChangedLocked()` の
`gapFrames = gapNanos * AUDIO_SAMPLE_RATE_HZ / 1e9`(line 413 付近)は、リングバッファ(=エンジンレート)に
挿入するので**エンジンレートで計算**する必要がある。`AUDIO_SAMPLE_RATE_HZ` 定数参照を**実効エンジンレート**に
差し替える。

---

## 5. Q5 — UI/UX と反映方式

### 置き場所
`SettingsBottomSheet.kt` に「音声品質」ラジオセクションを追加(解像度/保存先/構図ガイド/マイク入力と同じ
`RadioButton` 行スタイル)。3 択(標準 / ハイレゾ96 / ハイレゾ192)。加えて AUDIO パネル(`MainScreen` の
`AudioStatsRow` 付近)に**実確定フォーマットラベル**(§3 の `audioFormatLabel`)を表示。

### 反映方式(ライブ vs 要再起動)
- **ビット深度のみ(将来 Phase 3)**: エンジンは float 48k のまま。次回 `startRecording()` から反映。再起動不要。
- **サンプルレート変更(本機能の本体)**: Oboe ストリームのレート・リングバッファ容量・DSP 係数が変わるため
  **オーディオエンジンの stop→再 start(または新設 `reconfigure(sampleRate)` ネイティブ API)が必要**。
  - **プレビュー中に設定変更**: エンジンを再起動する(メーターが一瞬途切れるが許容)。既存の
    `setPreferredInputKind()` → `dispatchAudioDeviceSetChanged()` → `sessionMutex` 直列化の**パターンを踏襲**するが、
    デバイス reopen より重い(リングバッファ/DSP 再構築込みの full restart)ので、専用の
    `setAudioQuality(quality)` → `pipelineScope.launch { sessionMutex.withLock { restartAudioEngineForQualityChange() } }`
    を新設する。
  - **録画中は変更不可**(`selectVideoConfig` が録画中 no-op なのと同じ)。UI 側も録画中はラジオを disable。

### 設定フロー(既存の配線に合わせる)
`SettingsBottomSheet` → `viewModel.setAudioQuality(quality)` → `RecordingPipeline.setAudioQuality(quality)` →
(必要なら)エンジン再起動 + `NativeEngineBridge` へレート伝達。`selectVideoConfig`/`setStorageLocation`/
`setAudioInputPreference` と同型の追加。

---

## 6. ファイル別・変更内容(実装チェックリスト)

### 6.1 ネイティブ (C++)
| ファイル | 変更 |
|---|---|
| `cpp/engine/OboeFullDuplexEngine.h` | `kSampleRate` constexpr を廃し実行時 `sampleRateHz_` メンバ化。`start()`/`reopenInputStream()` にレート引数追加、または `configure(sampleRateHz)` を新設。リングバッファは最大レート(192k*10)で確保。DSP メンバはレート変更時に再構築できるよう(再代入 or 再 open シーケンスで作り直す)。 |
| `cpp/engine/OboeFullDuplexEngine.cpp` | `openInputStreamLocked()`: `setSampleRate(sampleRateHz_)`、**ハイレゾ経路ではサンプルレート変換を禁止**(§3)。post-open ガードを「要求レート unless フォールバック中」に対応。`setMonitoringEnabled()`: 出力ストリームを `setSampleRate(sampleRateHz_)` で開く(§4 モニタ修正)。`onAudioReady()` の `kChannelCount` 参照を実効チャンネル数へ。 |
| `cpp/dsp/HighPassFilter.h`, `cpp/dsp/BiquadEq.h` | `kRampSamples = 240` → `round(sampleRateHz_ * 0.005)`(コンストラクタで算出しメンバ化)。 |
| **新規** `cpp/dsp/Decimator.{h,cpp}` | 整数比(2:1 / 4:1)アンチエイリアス FIR デシメータ。float in/out。host GTest 付き。**MVP で 96→48(2:1)だけ、Phase 2 で 4:1**。 |
| `cpp/jni/native-lib.cpp` | 新規 JNI: `nativeConfigureSampleRate`(または `nativeStart` にレート引数追加)、`nativeGetActualSampleRate`(実確定レート照会)。`EngineGuard`/`g_registry` パターンを踏襲。 |
| `cpp/test/` | `Decimator` の GTest、二重レート anchor 換算の回帰テスト。 |

### 6.2 Kotlin — 音声/エンコード
| ファイル | 変更 |
|---|---|
| `audio/NativeEngineBridge.kt` | `start(deviceId, sampleRateHz)` / `reconfigure(sampleRateHz)` / `getActualSampleRate(): Int` を追加(外部 native 宣言も)。 |
| **新規** `encoder/WavFileWriter.kt` | RIFF/WAVE(fmt タグ=3 IEEE float, 32bit, Nch, sampleRate)ヘッダ書き出し + float インターリーブを LE でストリーム書き込み。**close 時に RIFF/data のサイズフィールドを back-patch**(§6.4)。4GB 近接でのセグメント切替に対応。 |
| `encoder/AudioEncoder.kt`(または新規 `HiResAudioSink` を内包) | drain ループで読んだ float ブロックを **(1) WAV writer に無変換で書き、(2) `Decimator` で 48k 化してから既存 PcmDither→AAC** に渡すファンアウトに変更。`seedAudioAnchor()` の `cumulativeSampleCount = framePosition` を **`framePosition / decimationFactor`** に(§4)。`FRAMES_PER_BLOCK`/`BUFFER_EMPTY_SLEEP_MS` をレート連動に(§6.5)。ハイレゾ無効時(標準)は WAV/decimator を完全バイパス(現状と同一挙動)。 |
| `pipeline/RecordingPipeline.kt` | `AUDIO_SAMPLE_RATE_HZ` を「実効エンジンレート」変数に(標準=48k)。`setAudioQuality(quality)` 追加(録画中 no-op、プレビュー中はエンジン再起動)。`startRecording()` で AudioEncoder に WAV 出力パス(take dir 内 `AuCamPRO_<ts>_segment_<i>.wav`)と目標フォーマットを渡す。`insertSilence` の gapFrames 計算をエンジンレートに。停止シーケンスで WAV を finalize。`emergencyFinalizeCurrentSegment()` に **現在の .wav ヘッダ確定**を追加(§6.4)。`exportToPublicMoviesIfRequested()` に WAV の MediaStore.Audio エクスポートを併設(§6.4)。`onAudioFormatChanged` コールバック新設。 |
| `audio/AudioDeviceRouter.kt` | 変更ほぼ不要(デバイス選択のみ)。必要なら「このデバイスがハイレゾ候補か」のヒント用途で `AudioDeviceInfo.getSampleRates()` を**参考情報**として露出(判定の主体は §3 の Oboe 交渉)。 |

### 6.3 Kotlin — UI / 状態 / 永続化
| ファイル | 変更 |
|---|---|
| **新規 enum** `ui/viewmodel/`(例 `AudioQuality`) | `Standard(48000)`, `HiRes96(96000)`, `HiRes192(192000)`。ラベル/実レート保持。 |
| `ui/viewmodel/CameraUiState.kt` | `SettingsState` に `audioQuality: AudioQuality = Standard`。トップレベルに `audioFormatLabel: String`(実確定フォーマット表示)。 |
| `ui/viewmodel/CameraControlViewModel.kt` | `setAudioQuality(quality)`(pipeline へ委譲、state 更新、prefs 保存)。`pipeline.onAudioFormatChanged` を collect して `audioFormatLabel` を更新。`restorePersistedSettings` に audioQuality 復元を追加。 |
| `ui/components/SettingsBottomSheet.kt` | 「音声品質」ラジオセクション追加(録画中 disable)。 |
| `ui/MainScreen.kt`(AUDIO タブ) | `audioFormatLabel` を INPUT 行付近に表示。 |
| `utils/UserPreferencesStore.kt` | `KEY_AUDIO_QUALITY` 追加、`Saved` に `audioQuality`、load/save に配線(enum name 文字列で既存 InputKind と同じやり方)。 |

### 6.4 WAV のライフサイクル(セグメント/クラッシュセーフ/保存先)
- **セグメント分割**: WAV も MP4 と同じ take タイムスタンプ + segment index で命名
  (`AuCamPRO_<takeTimestampMs>_segment_<index>.wav`)。WAV の回転は**時間ベースのみ**(キーフレーム制約が
  無いので `SegmentRotationPlanner` より単純)。同じ `segmentDurationMinutes` を使い、書き込んだフレーム数
  換算の経過時間で新ファイルへ。→ これにより **各 WAV が 4GB 上限を自然に下回る**(5分 @192k/32f ≈ 460MB)。
  - 4GB は RIFF のサイズフィールドが 32bit であることに由来する**正しさの制約**。W64/RF64 で 4GB 超に
    する手もあるが、既存のセグメント分割に相乗りする方が単純かつクラッシュセーフ物語とも整合するので
    **セグメント WAV を採用**。
- **クラッシュセーフ**: MP4 と同じく「クラッシュで失うのは現在の 1 セグメントだけ」を維持。ただし WAV は
  ストリーミング書き込みで**ヘッダのサイズフィールドが未確定のまま**なので、`emergencyFinalizeCurrentSegment()`
  で現在の .wav の RIFF/data サイズを実書き込み量で back-patch する処理を追加(MP4 の `muxer.stop()` に相当)。
- **保存先**: `StorageLocation` に従う。`PublicMovies` 選択時は WAV を `MediaStore.Audio`(例: `Music/AuCamPRO`)へ
  エクスポート(`exportToPublicMoviesIfRequested` と同じ IS_PENDING → copy → clear パターン)。`AppPrivate` は
  take dir に残す。MediaStore.Audio エクスポートの体裁は Phase 3 で磨いてもよい(MVP は AppPrivate 保存でも可)。

### 6.5 「48kHz にキーされている箇所」完全棚卸し
1. `OboeFullDuplexEngine.h`: `kSampleRate`(constexpr) — **runtime 化**。
2. `OboeFullDuplexEngine.h`: `kRingBufferCapacityFrames = kSampleRate*10` — **最大レートで確保**。
3. `OboeFullDuplexEngine.cpp`: 入力ストリーム `setSampleRate(kSampleRate)` + post-open ガード — **runtime レート**。
4. `OboeFullDuplexEngine.cpp`: **モニタ出力 `setSampleRate(kSampleRate)`** — 見落とし注意、runtime レート化(§4)。
5. `dsp/HighPassFilter.h` `kRampSamples = 240` / `dsp/BiquadEq.h` `kRampSamples = 240` — **`sampleRateHz*0.005` に式化**。
6. `dsp/PeakRmsMeter.cpp`: 係数は sampleRate 引数から算出済み — **runtime レートを渡すだけ**。
7. `RecordingPipeline.kt`: `AUDIO_SAMPLE_RATE_HZ`(companion) — **実効エンジンレート変数**。
8. `RecordingPipeline.kt`: `insertSilence` の gapFrames 計算 — **エンジンレート**。
9. `AudioEncoder.kt`: `FRAMES_PER_BLOCK=512` / `BUFFER_EMPTY_SLEEP_MS=5` — **レート連動**(§4)。
10. DSP チェーン全体はレート変更時に**再構築**が必要(コンストラクタでレートを焼くため)。
11. `AudioEncoder` に渡す AAC の `sampleRateHz` は **48k のまま**(デシメート後)。ここだけはハイレゾでも変えない。

---

## 7. フェーズ計画(複雑度/リスク付き)

### Phase 1 = 出荷可能なハイレゾ MVP: **96kHz / 32-bit float WAV サイドカー**
ユーザーが求めた「高サンプルレート」を実際に届けるのが MVP の要件。48k-only はゴールではない。
- 含む: エンジンのレート runtime 化、DSP 再構築、96→48 デシメータ(2:1)、モニタ出力レート修正、
  能力検知 + 48k フォールバック + 実レート UI 表示、`WavFileWriter`、セグメント WAV + クラッシュセーフ、
  設定トグル、永続化。
- **実装順の推奨(de-risk)**:
  - **1a(内部マイルストーン, 出荷点ではない)**: レート変更**なし**の 48k のまま WAV サイドカーを追加し、
    「追加シンクの配線(drain ファンアウト・WAV writer・セグメント・クラッシュセーフ・保存先)」だけを
    先に実機で通す。この時点では decimator も不要(48k→48k)なので二重レートのバグ源に触れずに配管を固める。
  - **1b**: エンジンを 96k runtime 化し、decimator と二重レート anchor 換算、能力検知/フォールバックを載せる。
  - 出荷点は **1b 完了時**(=96k WAV が実際に出る)。1a を「MVP」と呼ばないこと(実装者がハイレゾ未達で
    完了と誤認するため)。
- 複雑度: 中〜大。リスク: **中〜高**(二重レート anchor 換算、Oboe の 96k ネイティブ交渉、モニタ出力レート、
  実機での 96k デバイス依存)。§8 の未確定リスクを実機で潰すことが前提。

### Phase 2 = 192kHz オプション
- Phase 1 のレートパラメータ化からほぼ自然に出る。新規実装は 4:1 デシメータと、192→96→48 の
  フォールバックラダー、192k での FRAMES_PER_BLOCK 調整。
- 複雑度: 小〜中。リスク: 中(192k ネイティブ対応はさらに機種依存が強い。ほぼ USB I/F 専用)。

### Phase 3 = フォーマット/ビット深度ピッカー・その他拡張
- 24-bit 整数 PCM WAV(float→int24 + ディザ)、pre-DSP raw 録音オプション、FLAC(可逆圧縮・自前コンテナ)、
  MediaStore.Audio エクスポートの体裁向上、WAV とビデオの厳密なファイルペアリング/命名メタ。
- 複雑度: 各小。リスク: 低(いずれも追加 DSP or UI で、コア経路に触れない)。

---

## 8. 未確定リスク(実機検証なしに断定できない事項 — 明示)

- **R1 (低影響)**: `MediaMuxer` が MP4 で `MIMETYPE_AUDIO_RAW` トラックを扱えない、という前提は API 知識に
  基づく。本設計は MP4 生 PCM を使わない(WAV サイドカー)ので load-bearing ではないが、断定はしていない。
- **R2 (高影響)**: Sony SO-51C(および他機種)で、**外付け USB オーディオ I/F 経由で Oboe が 96/192kHz を
  ネイティブに(サンプルレート変換なしで)開けるか**は未検証。内蔵マイクは 48k 止まりの公算大。→ 能力検知 +
  48k フォールバックがあるので「録れなくなる」ことは無いが、「ハイレゾが実際に効く条件」は実機で要確認。
- **R3 (高影響)**: 96/192kHz キャプチャ時に `AudioStream::getTimestamp(CLOCK_MONOTONIC)`(=`getInputTimestamp`)が
  48k と同様に安定して frame-correlation を返すか未検証。§4 の二重レート anchor 換算は、この相関が取れる前提。
  ARCHITECTURE.md の教訓どおり、実機で**フォールバック警告(壁時計 anchor)が出ていないこと**を必ず確認する。
- **R4 (中影響)**: アンチエイリアス・デシメータの音質(通過帯域リップル/折り返し抑圧)は host GTest で
  数値検証できるが、「聴感上正しいか」は実機/実音源でのみ最終確認可能。
- **R5 (中影響)**: 96k で回すと RT コールバックの処理時間予算が半分になり(バースト長が半分)、DSP + リング
  バッファ書き込みが**xrun 予算内に収まるか**は実機依存。§6.5 の FRAMES_PER_BLOCK 調整で drain 側は救えるが、
  RT 側の余裕は実機で `hardwareXRunCount()`/`ringBufferOverrunCount()` を見て確認する。
- **R6 (低影響)**: 32-bit float WAV の民生プレイヤー互換。プロ DAW は問題ないが、一部の簡易プレイヤーが
  fmt タグ=3 を読めない可能性。→ 気になるなら Phase 3 で 24-bit PCM を追加。
- **R7 (中影響)**: プレビュー中のエンジン再起動(レート変更)が、`sessionMutex` 直列化下で
  カメラセッションやメーターポーリングと競合しないか。既存 `setPreferredInputKind` の reopen より重い操作
  なので、実機でロック/アンロック連打的な操作と併せて検証する。
```
