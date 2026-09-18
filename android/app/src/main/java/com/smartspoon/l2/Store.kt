package com.smartspoon.l2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/** 一次「口」记录。 */
data class Bite(
    val id: Long = 0,
    val dish: String,
    val weight: Double,
    val energy: Double,
    val at: Long,
)

/**
 * 本地数据库（系统自带的 SQLite，无第三方依赖）。
 *
 * 菜品、菜单、用餐记录、每一口明细、收藏、设置都在这里持久化——
 * 应用离线也能完整使用；服务器只用于「菜品识别」和可选的菜品库导入。
 */
class Store(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "smartspoon.db"

        /**
         * v1：收藏时间以**格式化好的文本**存在 `dishes.favorite_at`，用餐起止时间在
         *     `meals` 里本来就是时间戳，但读出来立刻格式化成字符串。
         * v2：「时间显示年 / 时间显示秒」要真的生效，于是收藏时间也改存**时间戳**
         *     （新增 `favorite_at_ms`），格式化全部推迟到显示的最后一刻（见 [Units]）。
         */
        const val DB_VERSION = 2

        /** 首次安装时写入的示例菜品（之后完全由用户增删改）。 */
        private val SEED_DISHES = listOf(
            Triple("海鲜比萨", "肉类", 2.86 to "🍕"),
            Triple("蛋糕", "水果", 4.11 to "🍰"),
            Triple("巨无霸汉堡", "肉类", 3.57 to "🍔"),
            Triple("孜然羊肉", "肉类", 8.20 to "🍖"),
            Triple("汽水", "水果", 1.80 to "🍹"),
            Triple("家常凉菜", "蔬菜", 1.05 to "🍃"),
            Triple("哈密瓜", "水果", 1.34 to "🍈"),
            Triple("小米粥", "蔬菜", 1.90 to "🍚"),
            Triple("清炒时蔬", "蔬菜", 0.96 to "🌿"),
            Triple("红烧肉", "肉类", 12.60 to "🍗"),
            Triple("牛肉面", "肉类", 3.10 to "🍜"),
            Triple("苹果", "水果", 2.18 to "🍎"),
        )

        private val SEED_MENUS = listOf(
            "菜单1" to listOf("孜然羊肉", "汽水", "家常凉菜", "哈密瓜"),
            "减脂餐" to listOf("孜然羊肉", "家常凉菜", "清炒时蔬"),
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dishes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              category TEXT NOT NULL,
              density REAL NOT NULL DEFAULT 0,
              times INTEGER NOT NULL DEFAULT 0,
              emoji TEXT NOT NULL DEFAULT '🍽',
              favorite INTEGER NOT NULL DEFAULT 0,
              favorite_at TEXT,
              favorite_at_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE menus(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE menu_items(
              menu_id INTEGER NOT NULL,
              dish_id INTEGER NOT NULL,
              position INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE meals(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              menu_name TEXT NOT NULL,
              started_at INTEGER NOT NULL,
              ended_at INTEGER NOT NULL,
              bites INTEGER NOT NULL DEFAULT 0,
              weight REAL NOT NULL DEFAULT 0,
              energy REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE bites(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              meal_id INTEGER NOT NULL,
              dish TEXT NOT NULL,
              weight REAL NOT NULL DEFAULT 0,
              energy REAL NOT NULL DEFAULT 0,
              at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        /*
         * v1 → v2：收藏时间由「格式化文本」改成「时间戳」。
         *
         * 这里**只加一列再回填，绝不重建表** —— 旧版那种 `DROP TABLE` 会把用户自己加的菜品、
         * 全部用餐记录、每一口明细一起抹掉，那是不可接受的。老行里 `favorite_at` 存的是
         * 「2026年9月12日 22:43」，解析得回来就写进新列，解析不回来就留空（显示成「—」）。
         */
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE dishes ADD COLUMN favorite_at_ms INTEGER")
            val legacy = mutableListOf<Pair<Long, String>>()
            db.rawQuery(
                "SELECT id, favorite_at FROM dishes WHERE favorite = 1 AND favorite_at IS NOT NULL",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    legacy.add(cursor.getLong(0) to (cursor.getString(1) ?: ""))
                }
            }
            legacy.forEach { (id, text) ->
                val millis = Units.parseLegacyDateTime(text)
                if (millis > 0L) {
                    db.execSQL(
                        "UPDATE dishes SET favorite_at_ms = ? WHERE id = ?",
                        arrayOf(millis, id),
                    )
                }
            }
        }
    }

    private fun seed(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val dishIds = mutableMapOf<String, Long>()
        SEED_DISHES.forEachIndexed { index, (name, category, rest) ->
            val values = ContentValues().apply {
                put("name", name)
                put("category", category)
                put("density", rest.first)
                put("times", 0)
                put("emoji", rest.second)
                put("favorite", if (index < 3) 1 else 0)
                put("favorite_at_ms", now - index * 86_400_000L)
            }
            dishIds[name] = db.insert("dishes", null, values)
        }
        SEED_MENUS.forEach { (menuName, dishes) ->
            val menuId = db.insert("menus", null, ContentValues().apply { put("name", menuName) })
            dishes.forEachIndexed { position, dishName ->
                dishIds[dishName]?.let { dishId ->
                    db.insert("menu_items", null, ContentValues().apply {
                        put("menu_id", menuId)
                        put("dish_id", dishId)
                        put("position", position)
                    })
                }
            }
        }
        // 三顿示例用餐记录，让记录/统计页首次进入就有内容
        val samples = listOf(
            Triple("菜单1", 2, listOf(
                Triple("孜然羊肉", 48.0, 221.0), Triple("汽水", 62.0, 240.0),
                Triple("家常凉菜", 40.0, 150.0), Triple("哈密瓜", 48.0, 120.0),
            )),
            Triple("张三的晚餐", 1, listOf(
                Triple("红烧肉", 55.0, 320.0), Triple("小米粥", 80.0, 170.0), Triple("苹果", 65.0, 110.0),
            )),
            Triple("菜单2", 3, listOf(
                Triple("牛肉面", 70.0, 260.0), Triple("清炒时蔬", 45.0, 90.0),
            )),
        )
        samples.forEachIndexed { index, (menuName, daysAgo, bites) ->
            val end = now - daysAgo * 86_400_000L
            val start = end - 47 * 60_000L
            val mealId = db.insert("meals", null, ContentValues().apply {
                put("menu_name", menuName)
                put("started_at", start)
                put("ended_at", end)
                put("bites", bites.size)
                put("weight", bites.sumOf { it.second })
                put("energy", bites.sumOf { it.third })
            })
            bites.forEachIndexed { i, (dish, weight, energy) ->
                db.insert("bites", null, ContentValues().apply {
                    put("meal_id", mealId)
                    put("dish", dish)
                    put("weight", weight)
                    put("energy", energy)
                    put("at", start + (i + 1) * 60_000L)
                })
            }
        }
    }

    /* ------------------------------------------------------------------ 菜品 */

    fun dishes(): MutableList<Food> {
        val list = mutableListOf<Food>()
        readableDatabase.rawQuery(
            "SELECT id, name, category, density, times, emoji FROM dishes ORDER BY id", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Food(
                        id = cursor.getLong(0).toString(),
                        name = cursor.getString(1),
                        category = cursor.getString(2),
                        density = cursor.getDouble(3),
                        times = cursor.getInt(4),
                        img = cursor.getString(5),
                    )
                )
            }
        }
        return list
    }

    /**
     * 收藏的菜品 → 收藏时间戳（毫秒）。0 = 这一行没有可用的收藏时间（v1 的旧文本没解析出来）。
     *
     * 返回时间戳而不是格式化好的文本，「时间显示年 / 秒」才会跟着设置走。
     */
    fun favorites(): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        readableDatabase.rawQuery(
            "SELECT id, favorite_at_ms, favorite_at FROM dishes WHERE favorite = 1", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val millis = if (cursor.isNull(1)) {
                    // v1 升级上来的行：新列为空，退回解析老文本
                    Units.parseLegacyDateTime(cursor.getString(2) ?: "")
                } else {
                    cursor.getLong(1)
                }
                map[cursor.getLong(0).toString()] = millis
            }
        }
        return map
    }

    fun toggleFavorite(dishId: String, favorite: Boolean) {
        writableDatabase.execSQL(
            "UPDATE dishes SET favorite = ?, favorite_at_ms = ? WHERE id = ?",
            arrayOf(if (favorite) 1 else 0, if (favorite) System.currentTimeMillis() else null, dishId),
        )
    }

    fun addDish(name: String, category: String, density: Double, emoji: String): Long {
        return writableDatabase.insert("dishes", null, ContentValues().apply {
            put("name", name)
            put("category", category)
            put("density", density)
            put("times", 0)
            put("emoji", emoji)
        })
    }

    fun updateDish(dishId: String, name: String, category: String, density: Double) {
        writableDatabase.execSQL(
            "UPDATE dishes SET name = ?, category = ?, density = ? WHERE id = ?",
            arrayOf(name, category, density, dishId),
        )
    }

    fun deleteDish(dishId: String) {
        writableDatabase.delete("dishes", "id = ?", arrayOf(dishId))
        writableDatabase.delete("menu_items", "dish_id = ?", arrayOf(dishId))
    }

    fun bumpTimes(dishIds: List<String>) {
        dishIds.forEach {
            writableDatabase.execSQL("UPDATE dishes SET times = times + 1 WHERE id = ?", arrayOf(it))
        }
    }

    /** 从服务器导入菜品库（按名称去重），返回新增数量。 */
    fun importFoods(foods: List<Food>): Int {
        val existing = dishes().map { it.name }.toMutableSet()
        var added = 0
        foods.forEach { food ->
            if (existing.add(food.name)) {
                val emoji = if (food.img.endsWith(".svg")) State.emoji[food.img] ?: "🍽" else food.img
                addDish(food.name, food.category, food.density, emoji)
                added++
            }
        }
        return added
    }

    /* ------------------------------------------------------------------ 菜单 */

    fun menus(): MutableList<MenuDef> {
        val list = mutableListOf<MenuDef>()
        readableDatabase.rawQuery("SELECT id, name FROM menus ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                list.add(MenuDef(id.toString(), cursor.getString(1), menuItems(id.toString())))
            }
        }
        return list
    }

    private fun menuItems(menuId: String): List<String> {
        val items = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT dish_id FROM menu_items WHERE menu_id = ? ORDER BY position", arrayOf(menuId)
        ).use { cursor ->
            while (cursor.moveToNext()) items.add(cursor.getLong(0).toString())
        }
        return items
    }

    fun createMenu(name: String, dishIds: List<String>): Long {
        val menuId = writableDatabase.insert("menus", null, ContentValues().apply { put("name", name) })
        dishIds.forEachIndexed { position, dishId ->
            writableDatabase.insert("menu_items", null, ContentValues().apply {
                put("menu_id", menuId)
                put("dish_id", dishId.toLongOrNull() ?: 0L)
                put("position", position)
            })
        }
        return menuId
    }

    fun deleteMenu(menuId: String) {
        writableDatabase.delete("menus", "id = ?", arrayOf(menuId))
        writableDatabase.delete("menu_items", "menu_id = ?", arrayOf(menuId))
    }

    /* ------------------------------------------------------------------ 用餐 */

    /** 保存一顿饭（含每一口明细），返回记录 id。 */
    fun saveMeal(menuName: String, startedAt: Long, endedAt: Long, bites: List<Bite>): Long {
        val weight = bites.sumOf { it.weight }
        val energy = bites.sumOf { it.energy }
        val mealId = writableDatabase.insert("meals", null, ContentValues().apply {
            put("menu_name", menuName)
            put("started_at", startedAt)
            put("ended_at", endedAt)
            put("bites", bites.size)
            put("weight", weight)
            put("energy", energy)
        })
        bites.forEach { bite ->
            writableDatabase.insert("bites", null, ContentValues().apply {
                put("meal_id", mealId)
                put("dish", bite.dish)
                put("weight", bite.weight)
                put("energy", bite.energy)
                put("at", bite.at)
            })
        }
        return mealId
    }

    fun updateMeal(mealId: Long, bites: Int, weight: Double, energy: Double) {
        writableDatabase.execSQL(
            "UPDATE meals SET bites = ?, weight = ?, energy = ? WHERE id = ?",
            arrayOf(bites.toString(), weight.toString(), energy.toString(), mealId.toString()),
        )
    }

    fun deleteMeal(mealId: Long) {
        writableDatabase.delete("meals", "id = ?", arrayOf(mealId.toString()))
        writableDatabase.delete("bites", "meal_id = ?", arrayOf(mealId.toString()))
    }

    fun meals(): List<MealRecord> {
        val list = mutableListOf<MealRecord>()
        readableDatabase.rawQuery(
            "SELECT id, menu_name, started_at, ended_at, bites, weight, energy FROM meals ORDER BY ended_at DESC",
            null,
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                index++
                val id = cursor.getLong(0)
                list.add(
                    MealRecord(
                        no = index,
                        menu = cursor.getString(1),
                        foods = biteDishes(id),
                        bites = cursor.getInt(4),
                        // 列是 REAL：这里读成 Double，别在小数位上提前取整 ——
                        // 结果页刚算出的 122.99 kJ 与记录列表里的 122 kJ 会因此对不上
                        weight = cursor.getDouble(5),
                        energy = cursor.getDouble(6),
                        start = Units.dateTime(cursor.getLong(2)),
                        end = Units.dateTime(cursor.getLong(3)),
                        id = id,
                        endedAt = cursor.getLong(3),
                        startedAt = cursor.getLong(2),
                    )
                )
            }
        }
        return list
    }

    private fun biteDishes(mealId: Long): List<String> {
        val names = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT dish FROM bites WHERE meal_id = ? ORDER BY at", arrayOf(mealId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (!names.contains(name)) names.add(name)
            }
        }
        return names
    }

    fun bites(mealId: Long): List<Bite> {
        val list = mutableListOf<Bite>()
        readableDatabase.rawQuery(
            "SELECT id, dish, weight, energy, at FROM bites WHERE meal_id = ? ORDER BY at",
            arrayOf(mealId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Bite(
                        id = cursor.getLong(0),
                        dish = cursor.getString(1),
                        weight = cursor.getDouble(2),
                        energy = cursor.getDouble(3),
                        at = cursor.getLong(4),
                    )
                )
            }
        }
        return list
    }

    /* ------------------------------------------------------------- 统计 */

    fun stats(): List<Pair<String, String>> {
        var minutes = 0L
        var bites = 0
        var weight = 0.0
        var energy = 0.0
        var count = 0
        readableDatabase.rawQuery(
            "SELECT started_at, ended_at, bites, weight, energy FROM meals", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                minutes += (cursor.getLong(1) - cursor.getLong(0)) / 60_000L
                bites += cursor.getInt(2)
                weight += cursor.getDouble(3)
                energy += cursor.getDouble(4)
                count++
            }
        }
        return listOf(
            "总用餐时间" to formatDuration(minutes),
            "总记录口数" to bites.toString(),
            "总摄入重量" to Units.weight(weight),
            "总摄入能量" to Units.energy(energy),
            "总餐数" to count.toString(),
        )
    }

    /**
     * 折线图的原始数据。库里的能量按 kJ 存，这里**就按当前热量单位换算好**再交出去 ——
     * 数值、刻度、轴标签必须一起换，否则选了 kcal 之后图上还写着「摄入能量/kJ」。
     */
    fun chartData(): ChartData {
        val points = mutableListOf<ChartPoint>()
        readableDatabase.rawQuery(
            "SELECT ended_at, energy FROM meals ORDER BY ended_at", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val energy = Units.energyValue(cursor.getDouble(1))
                points.add(ChartPoint(Units.date(cursor.getLong(0)), energy.toInt()))
            }
        }
        val max = points.maxOfOrNull { it.y } ?: 0
        // 刻度步长跟着单位走：kcal 的数值比 kJ 小 4 倍多，还用 200 一档会把图压成一条线
        val unit = if (State.energyUnit == "kcal") 50 else 200
        val step = maxOf(unit, ((max + unit - 1) / unit) * unit)
        val ticks = (0..6).map { it * step / 6 }
        return ChartData(
            xLabel = "用餐完成时间",
            yLabel = Units.energyAxisLabel(),
            yTicks = ticks,
            xTicks = points.map { it.x },
            points = points,
        )
    }

    private fun formatDuration(minutes: Long): String {
        if (minutes < 60) return "$minutes 分钟"
        return "${minutes / 60}小时${minutes % 60}分钟"
    }

    /** 统计某天的口数（用于「今天」概览，留作扩展）。 */
    fun bitesToday(): Int {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var count = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM bites WHERE at >= ?", arrayOf(calendar.timeInMillis.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) count = cursor.getInt(0)
        }
        return count
    }
}
