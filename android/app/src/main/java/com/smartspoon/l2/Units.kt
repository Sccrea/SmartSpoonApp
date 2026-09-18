package com.smartspoon.l2

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单位换算与格式化 —— 「设置 → 杂项」里那四项（时间显示年 / 时间显示秒 / 热量单位 / 重量单位）
 * 真正生效的地方。
 *
 * 约定（这是本文件存在的全部意义）：
 *
 * - **内部一律用基准单位存**：热量存 kJ、重量存 g、时间存毫秒时间戳。
 * - **只在显示的那一刻换算**：界面、弹窗、统计、图表都调用这里，而不是各自拼
 *   `"${value} kJ"`。以前那些硬编码的单位串正是「设置里能改、界面上不变」的原因。
 * - 于是改单位只要改 [State] 里的那个字段，所有位置下一帧就跟着变，不需要重读数据库。
 *
 * 唯一需要重读的是「统计数据汇总」与折线图：它们的文字是读库时算好放进 [State.data] 的，
 * 所以设置页改完单位会顺手重读一次（见 `MiscScreen`）。
 */
object Units {

    /** 1 kcal = 4.184 kJ（与旧版 `energyText` 用的是同一个常数）。 */
    const val KJ_PER_KCAL = 4.184

    /** 1 两 = 50 g（市制，中国境内常用）。 */
    const val GRAMS_PER_LIANG = 50.0

    /* ------------------------------------------------------------ 热量 */

    /** 把基准单位（kJ）换算成当前显示单位下的**数值**。 */
    fun energyValue(kj: Double): Double =
        if (State.energyUnit == "kcal") kj / KJ_PER_KCAL else kj

    /** 当前热量单位下的数值 → 基准单位 kJ（「修正数据」把用户输入写回时用）。 */
    fun toKj(display: Double): Double =
        if (State.energyUnit == "kcal") display * KJ_PER_KCAL else display

    fun energyUnitLabel(): String = if (State.energyUnit == "kcal") "kcal" else "kJ"

    /** 当前热量单位下的数值，四舍五入到 1 位小数（供输入框预填）。 */
    fun energyNumber(kj: Double): String = trim(round1(energyValue(kj)))

    /** `123.0 kJ` / `29.4 kcal`。 */
    fun energy(kj: Double): String = "${energyNumber(kj)} ${energyUnitLabel()}"

    /** 只在数值后加单位，不换行的那种（图表轴标签用）。 */
    fun energyAxisLabel(): String = "摄入能量/${energyUnitLabel()}"

    /* ------------------------------------------------------------ 重量 */

    /** 把基准单位（g）换算成当前显示单位下的**数值**。 */
    fun weightValue(grams: Double): Double = when (State.weightUnit) {
        "kg" -> grams / 1000.0
        "两" -> grams / GRAMS_PER_LIANG
        else -> grams
    }

    /** 当前重量单位下的数值 → 基准单位 g。 */
    fun toGrams(display: Double): Double = when (State.weightUnit) {
        "kg" -> display * 1000.0
        "两" -> display * GRAMS_PER_LIANG
        else -> display
    }

    fun weightUnitLabel(): String = State.weightUnit

    /**
     * 当前重量单位下的数值：g 取整、kg 保留 2 位、两保留 1 位。
     * 单位越小、数字越大，所以小数位跟着单位走，读数才不至于全是 0.00。
     */
    fun weightNumber(grams: Double): String = when (State.weightUnit) {
        "kg" -> trim(round2(weightValue(grams)))
        "两" -> trim(round1(weightValue(grams)))
        else -> trim(round1(grams))
    }

    /** `15 g` / `0.02 kg` / `0.3 两`。 */
    fun weight(grams: Double): String = "${weightNumber(grams)} ${weightUnitLabel()}"

    /* -------------------------------------------------------- 能量密度 */

    /** 当前的重量单位相当于多少克：g=1、kg=1000、两=50。 */
    private fun gramsPerWeightUnit(): Double = when (State.weightUnit) {
        "kg" -> 1000.0
        "两" -> GRAMS_PER_LIANG
        else -> 1.0
    }

    /**
     * 能量密度：库里的基准是「每克含多少 kJ」。
     *
     * 展示时**热量单位与重量单位都要换算** —— 选了 kg 就该显示成 `kcal/kg` 或 `kJ/kg`，
     * 选了「两」就是 `kJ/两`；而且**数值必须跟着一起变**（1 kJ/g = 1000 kJ/kg = 50 kJ/两），
     * 只把标签换个字就成了假数据。
     *
     * （早先这里只换热量单位、把 `/g` 写死，理由是"密度按克定义"；
     *   但用户在设置里选了重量单位，这一行却仍写 g，看起来就是设置没生效。）
     */
    fun densityValue(kjPerGram: Double): Double =
        energyValue(kjPerGram) * gramsPerWeightUnit()

    /** 当前显示单位下的密度数值 → 基准单位 kJ/g（编辑菜品时把输入写回库）。 */
    fun toDensity(display: Double): Double =
        toKj(display) / gramsPerWeightUnit()

    fun densityUnitLabel(): String = "${energyUnitLabel()}/${weightUnitLabel()}"

    /**
     * 每克时保留两位小数（沿用旧版的 `8.20`）；
     * 换成 kg / 两 之后数值大得多（×1000 / ×50），留一位就够，不然一行全是零头。
     */
    fun densityNumber(kjPerGram: Double): String {
        val value = densityValue(kjPerGram)
        return if (State.weightUnit == "g") {
            "%.2f".format(Locale.US, value)
        } else {
            trim(round1(value))
        }
    }

    /** `8.20kJ/g` / `1.96kcal/g` / `1960kcal/kg` / `98kcal/两`。 */
    fun density(kjPerGram: Double): String = "${densityNumber(kjPerGram)}${densityUnitLabel()}"

    /* -------------------------------------------------------------- 时间 */

    /*
     * 「时间显示年」「时间显示秒」两个开关就体现在这两个格式串上。
     * 每次现建 SimpleDateFormat（按模式缓存），这样开关一改，下一次格式化立刻是新格式。
     */
    private val formats = mutableMapOf<String, SimpleDateFormat>()

    private fun format(pattern: String, millis: Long): String {
        val f = formats.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.CHINA) }
        return f.format(Date(millis))
    }

    /** 时间戳 → 界面上的「日期 + 时刻」，受两个时间开关控制。 */
    fun dateTime(millis: Long): String {
        val pattern = buildString {
            append(if (State.showYear) "yyyy年M月d日" else "M月d日")
            append(" HH:mm")
            if (State.showSecond) append(":ss")
        }
        return format(pattern, millis)
    }

    /** 只有日期（折线图的 x 轴刻度），同样受「时间显示年」控制。 */
    fun date(millis: Long): String =
        format(if (State.showYear) "yyyy/M/d" else "M/d", millis)

    /**
     * 把「2026年9月12日 22:43」这种**旧版本存下来的文本**还原成时间戳。
     *
     * v1 的库把收藏时间以格式化文本存进 `dishes.favorite_at`；v2 改成存时间戳，
     * 升级时要用它把老行回填。认不出来就返回 0（界面显示「—」）。
     */
    fun parseLegacyDateTime(text: String): Long {
        if (text.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).parse(text)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /* -------------------------------------------------------------- 小工具 */

    private fun round1(v: Double): Double = Math.round(v * 10) / 10.0

    private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

    /** 去掉 `12.0` 这种没意义的 `.0`，但保留 `12.5`。 */
    private fun trim(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}
