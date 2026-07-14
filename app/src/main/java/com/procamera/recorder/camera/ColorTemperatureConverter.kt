package com.procamera.recorder.camera

import android.hardware.camera2.params.RggbChannelVector

/**
 * Converts a color temperature in Kelvin (§4.1 Manual WB, 2500K-8000K) into the RGGB
 * sensor gains Camera2 expects for `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` +
 * `COLOR_CORRECTION_GAINS`, and back.
 *
 * **実装の経緯**: 当初はTanner Hellandの黒体放射近似式を使用していたが、実機で強烈な
 * 緑被りが発生する問題があった(Bayerセンサーの緑チャンネル感度特性を無視していたため)。
 * これを、緑を基準(1.0固定)としてR/Bチャンネルのゲインを線形補間する、スマホの
 * Bayerセンサーに特化したシンプルなモデルに置き換えた: 暖色(2500K)では青チャンネルの
 * 生センサー応答が弱いため青ゲインを強くブースト(2.8)し赤は控えめ(1.4)に、寒色
 * (8000K)ではその逆(赤2.8、青1.4)にする。
 *
 * **確信度の明示**: この線形補間モデルはCIE標準観測者やPlanckian locusから厳密に
 * 導出されたものではなく、経験的に妥当な範囲のゲイン値を割り当てた近似である。実際の
 * 色再現(センサーのカラーフィルタ特性・ISPのレンダリング意図)は端末依存であり、
 * Phase5の実機検証(グレーカード等の目視確認)なしには色精度を断定できない。
 *
 * **テスト容易性**: `android.hardware.camera2.params.RggbChannelVector`のアクセサ
 * (`getRed()`等)は、plain JUnit(Robolectric無し)下では「not mocked」で例外を投げる
 * (§Phase3の`android.util.Range`と同じ制約)。そのため計算コア(`kelvinToRgbGains`/
 * `rgbGainsToKelvin`、プレーンな`Float`のみを扱う)とフレームワーク結線
 * (`kelvinToRggbGains`/`rggbGainsToKelvin`、`RggbChannelVector`を扱う)を分離している
 * ——前者のみJUnitで検証可能。
 */
object ColorTemperatureConverter {

    private const val MIN_KELVIN = 2500.0
    private const val MAX_KELVIN = 8000.0

    // スマホのBayerセンサーにおける典型的なWBゲインの範囲。
    // 色温度が低い(2500K 暖色) = 青を強くブースト(2.8)、赤は弱め(1.4)。
    private const val R_GAIN_2500 = 1.4
    private const val B_GAIN_2500 = 2.8

    // 色温度が高い(8000K 寒色) = 赤を強くブースト(2.8)、青は弱め(1.4)。
    private const val R_GAIN_8000 = 2.8
    private const val B_GAIN_8000 = 1.4

    /**
     * Pure calculation core (JUnit-testable — see class doc). Green is always pinned to
     * exactly 1.0; red/blue are linearly interpolated between the warm (2500K) and cool
     * (8000K) endpoints.
     *
     * @param kelvin clamped to [MIN_KELVIN, MAX_KELVIN] (§4.1's specified UI range).
     * @return (red, green, blue) gains.
     */
    fun kelvinToRgbGains(kelvin: Double): Triple<Float, Float, Float> {
        val k = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
        val t = (k - MIN_KELVIN) / (MAX_KELVIN - MIN_KELVIN)

        val r = R_GAIN_2500 + t * (R_GAIN_8000 - R_GAIN_2500)
        val b = B_GAIN_2500 + t * (B_GAIN_8000 - B_GAIN_2500)

        return Triple(r.toFloat(), 1.0f, b.toFloat())
    }

    /** Framework-facing wrapper around [kelvinToRgbGains] for `COLOR_CORRECTION_GAINS`. */
    fun kelvinToRggbGains(kelvin: Double): RggbChannelVector {
        val (r, g, b) = kelvinToRgbGains(kelvin)
        return RggbChannelVector(r, g, g, b)
    }

    /**
     * Pure inverse core (JUnit-testable). Used to surface the ISP's Auto-AWB measured
     * gains as a Kelvin value in the UI (e.g. when switching Auto -> Manual). Averages
     * the two independent (red, blue) estimates to reduce sensitivity to whichever
     * channel the ISP weighted more heavily.
     */
    fun rgbGainsToKelvin(red: Float, blue: Float): Double {
        val tR = (red - R_GAIN_2500) / (R_GAIN_8000 - R_GAIN_2500)
        val tB = (blue - B_GAIN_2500) / (B_GAIN_8000 - B_GAIN_2500)
        val t = (tR + tB) / 2.0
        val k = MIN_KELVIN + t * (MAX_KELVIN - MIN_KELVIN)
        return k.coerceIn(MIN_KELVIN, MAX_KELVIN)
    }

    /** Framework-facing wrapper around [rgbGainsToKelvin] for a measured `RggbChannelVector`. */
    fun rggbGainsToKelvin(gains: RggbChannelVector): Double = rgbGainsToKelvin(gains.red, gains.blue)
}
