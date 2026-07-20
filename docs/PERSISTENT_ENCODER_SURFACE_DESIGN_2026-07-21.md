# Persistent Encoder Surface — 設計 (Phase 2 着手)

最終更新: 2026-07-21
コード基準: `perf/camera-session-latency`(`72de367`まで)
前提: `docs/CAMERA_SESSION_LATENCY_2026-07-21.md`のPhase 1実測結果

## 目的とスコープ

Phase 1実測で判明した通り、`createCaptureSession`(sessionConfigure)が全条件で最大の単一
コスト(正常系205-218ms、1080p60 MANUALで483ms)。これは**同一解像度/fpsのまま録画を
開始・停止するたびに**発生している——`RecordingPipeline.startRecording()`が
`Preview+Histogram+JPEG`(プレビュー用セッション)から`Preview+Encoder`(録画用セッション)
へSurface集合を切り替えるために、毎回`CameraCaptureSession`を作り直しているため
(`CameraSessionController.reconfigureSession()`)。

**このPhase 2で解決する対象**: 同一解像度/fps/レンズのまま録画を繰り返し開始・停止する
ケースのセッション再生成コスト。

**このPhase 2で解決しない対象(スコープ外、明示)**:
- 解像度/fps変更 — `MediaCodec`の`InputSurface`は`configure()`時のサイズに固定されるため、
  Persistent Surfaceを使っても解像度が変わればセッション再構成は避けられない。
- レンズ切替 — 別`cameraId`を開き直す必要があり、Persistent Surfaceとは無関係に発生する。
- CaptureMode切替(Photo⇔Video) — 後述するが、これも1回のセッション再構成として許容する
  (元の仕様書の方針通り)。

## 現状の確認(コードから)

- `RecordingPipeline.startPreview()`は常に`Preview + Histogram + JPEG(Photo)`の3ストリーム
  でセッションを構成する(`repeatingTargets`はPhoto用JPEGリーダーを除外——過去に発見された
  「JPEGリーダーが毎フレーム全解像度エンコードしてしまう」バグの回避)。
- `RecordingPipeline.startRecording()`は`Preview + Encoder`の2ストリームで
  `reconfigureSession()`を呼ぶ。**`LuminanceHistogramReader`のクラスdocに明記されている
  通り、これは意図的な設計**: 「Deliberately not wired into the recording session...
  adding a stream to the already carefully-tuned preview+encoder recording combo was judged
  not worth the risk... The histogram simply stops updating (freezes on its last value) once
  a recording starts.」——つまり今の実装は「録画中はヒストグラムが凍結する」ことを**意図的に
  選んでいる**。この既存の挙動をPersistent Surface設計で壊さないことが要件。
- `CaptureMode`(Photo/Video)は現状**完全にUI/ViewModel層の概念**で、
  `RecordingPipeline`は一切関知していない(grep確認済み)。Photo modeでのみ📷ボタン/
  ハードウェアキーが`capturePhoto()`に到達し、Video modeでは`RecIndicator`が表示されるだけで
  写真撮影UIそのものが存在しない(`MainScreen.kt`確認済み)——つまり「Video mode中は
  写真撮影が起きない」は既にUI層で保証されている。

## 提案する設計

### セッション構成をCaptureModeごとに固定し、CaptureMode切替時のみ再構成する

```
Photo mode:  Preview + Histogram + JPEG          (現状と同じ、変更なし)
Video mode:  Preview + Histogram + Persistent Encoder Surface
```

Video mode用セッションは「Video modeに切り替わった時」に一度だけ構成し、**録画の開始・停止
では再構成しない**。録画開始・停止で変わるのは「どのSurfaceがrepeating requestの対象か」
だけにする——これは`CameraSessionController`が既に持っている
`outputSurfaces`(セッションに構成する全Surface) vs `repeatingTargets`(実際に repeating
requestが対象とするSurface)の分離をそのまま使う(Photo用JPEGリーダーを「セッションには
含めるがrepeatingには含めない」という既存パターンと同型)。

```
Video mode・プレビュー中:
  outputSurfaces  = [Preview, Histogram, PersistentEncoderSurface]
  repeatingTargets = [Preview, Histogram]              ← Encoderは配信対象外(アイドル)

録画開始:
  1. 新しいMediaCodecをPersistentEncoderSurfaceへbind + start()
  2. repeatingTargetsを [Preview, Histogram, PersistentEncoderSurface] へ更新
     (setRepeatingRequestのみ、createCaptureSessionは呼ばない)
  → ここでヒストグラムを録画中も維持するか、今の「凍結」挙動を保つかは選択可能(後述)

録画停止:
  1. repeatingTargetsを [Preview, Histogram] へ戻す(Encoderを配信対象から外す)
  2. MediaCodecをsignalEndOfStream→drain→stop→release(PersistentEncoderSurface自体は
     解放しない——次の録画でも同じSurfaceを再利用)
```

**ヒストグラムの録画中の扱いについて**: 今回はEncoderの`repeatingTargets`参加/離脱だけを
切り替える設計なので、ヒストグラムを「録画中も生かす」か「今まで通り凍結させる」かは
`repeatingTargets`にHistogramを含め続けるかどうかで自由に選べる、独立した決定になる。
**今回のPhase 2では既存の「録画中は凍結」という明示的な製品判断を変更しない**——録画開始時に
Histogramも`repeatingTargets`から外す(Encoderを足すのと同時にHistogramを引く)ことで、
今日と全く同じユーザー可視の挙動を保ったまま、セッション再生成だけをなくす。ヒストグラムを
録画中も動かす、という話は**別の機能追加として別途ユーザーに確認する**(このPhase 2の
スコープには含めない)。

### Persistent Surfaceの生成・破棄タイミング

`MediaCodec.createPersistentInputSurface()`はAPI 23+の静的ファクトリで、特定の`MediaCodec`
インスタンスに紐付かない`Surface`を1つ作る。以降、録画のたびに新しい`MediaCodec`を
`configure()`した後に`codec.setInputSurface(persistentSurface)`で束ね直す(現行の
`codec.createInputSurface()`の代わり)——`Surface`自体のアイデンティティは録画を跨いで
不変なので、`CameraCaptureSession`のSurface集合は変わらない。

このSurfaceの生成・破棄は「録画1回」ではなく「Video CaptureModeがアクティブな間」という、
今より一段広いライフタイムを持つ。`RecordingPipeline`がこれを所有し、Video modeに入った時に
1回生成、Photo modeへ切り替わった時/パイプライン全体のteardown時に解放する。

### 必要なコード変更(実装はまだしない、一覧のみ)

- `CameraSessionController`: 新規の軽量メソッド(例:
  `updateRepeatingTargets(newTargets: List<Surface>)`)——`activeSurfaces`を更新して
  既存の`activeRequestFactory`/`activeParams`から`setRepeatingRequest`を再発行するだけ。
  `abortCaptures`も`createCaptureSession`も呼ばない。
- `VideoEncoder`: 外部から渡された永続`Surface`を使うコンストラクタ経路(`setInputSurface`)
  を追加。既存の`createInputSurface()`経路(smoke-test/非Video-mode用途)は残す。
- `RecordingPipeline`: Persistent Surfaceの所有・生成・解放、`CaptureMode`を初めて知る
  必要が生じる(新規カップリング)——ViewModel側の`setCaptureMode()`と対になる
  `pipeline.setCaptureMode(mode)`のようなメソッドを追加し、Photo⇔Video切替時にのみ
  1回のセッション再構成を行う。録画開始・停止の実装は「新しいセッションを作る」から
  「repeatingTargetsを更新する」へ置き換え。
- `CameraCapabilityInspector`まわりは変更不要——解像度/fps変更時のセッション再構成は
  今まで通り(スコープ外)。

### 順序の制約(バックプレッシャー事故を避けるため)

`Surface`のBufferQueueは有限のスロットしかない。Encoderが起動・drainしていない状態で
カメラがそのSurfaceへ配信し続けると、キューが埋まってカメラHAL側がブロックし、
**repeating request全体(Previewも含む)が詰まる**リスクがある。したがって:

- **録画開始**: 新しいMediaCodecを`configure()`+`setInputSurface()`+`start()`で
  完全に起動させてから、`repeatingTargets`にEncoderを追加する(bind-before-target)。
- **録画停止**: `repeatingTargets`からEncoderを先に外してから、MediaCodecの
  `signalEndOfStream`→drain→`stop()`→`release()`を行う(untarget-before-unbind)。

この順序を守れば、Encoderがアイドル(未起動)の間は常に`repeatingTargets`から外れている
状態を維持できる。

## `Event.Started`→`firstVideoFrame`ギャップ(200-430ms)への示唆

原因は未確定。仮説: 新しいEncoder Surfaceへの配信を「初めて」始める瞬間は、カメラの
センサー/HALパイプライン(露出・AE収束状態を含む)がまだこのSurfaceに対して「温まって
いない」状態である可能性がある。Persistent Surface設計では、プレビュー中から同一
セッション・同一repeating request(Preview+Histogram)がすでに流れ続けており、録画開始時に
起きるのは「Encoderをrepeating対象に追加するだけ」——カメラ自体は録画開始前から連続して
フレームを配信し続けているため、このギャップが実測で縮むかどうかは有力な検証ポイントになる。
縮まなかった場合は、`CaptureResult`の到着タイムスタンプ(既にHALから取得済み)とEncoderの
`onOutputBufferAvailable`到着タイムスタンプを両方ログして、「カメラ側のフレーム到達遅延」と
「MediaCodec内部のエンコードパイプライン遅延」を切り分ける追加計測が必要。

## 低リスク項目(CaptureCallback再利用・同一パラメータ省略等)の判断について

Phase 1の実機データでは、本テストが「録画1サイクルのみ」の実施だったため
`PARAM_UPDATE`(スライダー操作相当)の呼び出し数が実際のマニュアル操作時より過小評価されて
いる可能性が高い。この判断のためだけに実機セッションをもう1回設けるのではなく、**次の
Persistent Surface実機フィージビリティ検証と同じセッションにスライダー操作を伴う録画を
数回追加する**ことで、両方を1回の実機作業で済ませることを提案する。

## 実機フィージビリティ確認が必要な項目(元の仕様書のPersistent Surface試作条件を踏襲)

- プレビューのみ(Encoderがrepeating対象外)の状態でEncoder Surfaceがセッションに含まれて
  いても、プレビューfpsが低下しない
- Encoder未起動中、HALがこのSurfaceへ書き込もうとして詰まらない(=repeatingTargets除外が
  正しく機能している)
- 録画開始時、新しいMediaCodecへ正常に接続・エンコードできる
- 録画停止後、同じPersistent Surfaceで再度録画開始できる(20回以上連続)
- CaptureMode切替(Photo⇔Video)時のセッション再構成が正常に機能する
- 解像度/fps変更時は今まで通りの全体再構成にフォールバックできる
- 画面消灯中の録画(既存の`detachPreviewSurface`パス)がPersistent Surface設計でも動作する
- Auto/Manual露出、24/30/60fpsそれぞれで動作する
- 高温下でも現状のPhase 1ベースラインより悪化しない
- A/V同期が今まで通り維持される

**この一覧が実機で確認できなければ、無理に採用しない**(元の仕様書の方針通り)。

## 次のステップ

1. 上記設計についてユーザー/レビューアの確認を得る。
2. 確認が得られたら、実装は`perf/camera-session-latency`とは別の実験ブランチ
   (例: `experiment/persistent-encoder-surface`)に分離し、mainには影響を与えない状態で
   進める。
3. 実装後、Phase 1と同じ計測基盤(`CameraSessionMetrics`)を使って前後比較する
   (`sessionConfigure`コスト・`Event.Started`→`firstVideoFrame`ギャップ・
   `setRepeatingRequest`呼び出し頻度が主な比較対象)。
4. 同じ実機セッションで、スライダー操作を伴う`PARAM_UPDATE`頻度の再測定も行い、
   低リスク項目(CaptureCallback再利用・同一パラメータ省略)に着手する価値があるか判断する。
5. フィージビリティが成立しなければ、このドキュメントに結果を追記し、Persistent Surfaceは
   不採用として記録する(コードは実験ブランチに残し、mainへは反映しない)。
