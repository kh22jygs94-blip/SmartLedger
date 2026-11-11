package com.example.smartledger.util

import kotlin.math.abs
import kotlin.math.min

/**
 * 时间序列降采样。
 *
 * LTTB（Largest Triangle Three Buckets）：把序列分成等宽的桶，
 * 每个桶挑一个「与相邻桶平均值构成的三角形面积最大」的点。
 * 相比等间隔抽样，峰值和拐点会被保留下来，曲线形状不会失真，
 * 绘制用的点数却能从上千降到和屏幕像素一个量级。
 */
object SeriesResampler {

    fun lttb(values: List<Float>, threshold: Int): List<Float> {
        if (threshold >= values.size || threshold < 3) return values

        val sampled = ArrayList<Float>(threshold)
        val bucketSize = (values.size - 2).toDouble() / (threshold - 2)
        sampled.add(values.first())

        var previous = 0
        for (index in 0 until threshold - 2) {
            // 下一个桶的平均点，作为当前桶选点的参照
            val nextStart = ((index + 1) * bucketSize).toInt() + 1
            val nextEnd = min(values.size, ((index + 2) * bucketSize).toInt() + 1)
            var averageX = 0.0
            var averageY = 0.0
            var count = 0
            for (point in nextStart until nextEnd) {
                averageX += point
                averageY += values[point]
                count++
            }
            if (count == 0) {
                averageX = (values.size - 1).toDouble()
                averageY = values.last().toDouble()
            } else {
                averageX /= count
                averageY /= count
            }

            val currentStart = (index * bucketSize).toInt() + 1
            val currentEnd = min(values.size, ((index + 1) * bucketSize).toInt() + 1)
            val previousY = values[previous].toDouble()
            var maxArea = -1.0
            var chosen = currentStart.coerceAtMost(values.size - 1)

            for (point in currentStart until currentEnd) {
                val area = abs(
                    (previous - averageX) * (values[point] - previousY) -
                        (previous - point.toDouble()) * (averageY - previousY)
                )
                if (area > maxArea) {
                    maxArea = area
                    chosen = point
                }
            }
            sampled.add(values[chosen])
            previous = chosen
        }
        sampled.add(values.last())
        return sampled
    }
}
