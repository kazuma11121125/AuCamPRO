package com.aucampro.recorder.camera

/**
 * User-facing露出モード(録画開始前にのみ選択、録画中の自動切替は行わない — see
 * docs/VIDEO_FPS_STUTTER_INVESTIGATION_2026-07-20.md §3.3の製品方針)。
 *
 * [MANUAL]がデフォルト(既存動作と完全互換)。[AUTO]は2026-07-20の実機調査で、高温時の
 * HAL内部フレーム損失(センサーは健全なのにHALのフレーム配信が崩れる)を大幅に軽減する
 * ことが確認された(`AE_MODE_OFF`常時強制時: video stream 3-5fps → `AE_MODE_ON`時:
 * 21-22fps、同程度の`CAM_CRITICAL`状態下)。ただし完全な解決ではないため、既存の
 * [MANUAL]を置き換えるのではなく、ユーザーが選べる並列の選択肢として追加する。
 */
enum class ExposureMode {
    AUTO,
    MANUAL,
}
