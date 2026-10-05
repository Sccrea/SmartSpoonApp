package com.equimeal.gramo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

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
 *
 * ## 一个账号一个库文件
 *
 * [dbName] 由调用方给（见 [Store.forAccount]）：未登录用 [DB_NAME]，
 * 登录后用 `smartspoon-<用户名>.db`。于是**换账号就是换库文件**，
 * 两个人的菜品、菜单、用餐记录天然互不可见 —— 不需要给每张表加 user_id、
 * 也不需要在每个查询里记得带上过滤条件（那种"忘了加 where"的 bug 最难发现）。
 */
class Store(
    context: Context,
    val dbName: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "smartspoon.db"

        /** 某个账号专属的库文件名（未登录 → 默认库）。 */
        fun forAccount(username: String?): String {
            val name = (username ?: "").trim()
            if (name.isEmpty()) return DB_NAME
            // 用户名已经限制成字母数字下划线汉字（见服务端 accounts.py），
            // 这里再兜一层：文件名里不允许出现路径分隔符等字符
            val safe = name.replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fa5]"), "_")
            return "smartspoon-$safe.db"
        }

        /**
         * v1：收藏时间以**格式化好的文本**存在 `dishes.favorite_at`，用餐起止时间在
         *     `meals` 里本来就是时间戳，但读出来立刻格式化成字符串。
         * v2：「时间显示年 / 时间显示秒」要真的生效，于是收藏时间也改存**时间戳**
         *     （新增 `favorite_at_ms`），格式化全部推迟到显示的最后一刻（见 [Units]）。
         */
        const val DB_VERSION = 2
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
        /*
         * 这里刻意**什么都不写**。
         *
         * 原先它会铺：
         * - 12 道种子菜品（海鲜比萨、蛋糕…）；
         * - 2 份种子菜单（菜单1、减脂餐）；
         * - 把前 3 道标成"已收藏"，收藏时间用 `now - index*24h` 编出来；
         * - 3 顿示例用餐记录（其中一份直接叫「张三的晚餐」）。
         *
         * 这些都是**模板数据**，问题不在于占地方，而在于它们和用户真实产生的数据混在同一张表里、
         * 长得一模一样：用户看到"收藏食物"里有 3 道从没收藏过的菜、用餐记录里有一顿没吃过的晚饭，
         * 却无法分辨也无法批量清掉。所以整个模板都去掉了 —— 新库就是空的，
         * 菜品/菜单/收藏/记录全部由用户自己产生（加菜、点收藏、走一遍用餐流程）。
         *
         * 各项功能都有对应的空状态提示（见 `ui/Components.kt` 的 `EmptyHint`）。
         */
    }

    /* ------------------------------------------------------------------ 菜品 */

    fun dishes(): MutableList<Food> {
        val list = mutableListOf<Food>()
        /*
         * 连 `favorite` 与收藏时间一起读出来。
         *
         * 为什么不让界面另外去查 [favorites]：三个列表都要"按收藏时间排序"，
         * 每行都回头查一次 map 既啰嗦又容易在某一页漏掉；直接挂在 [Food] 上，
         * 排序、显示"收藏时间"、判断有没有收藏都只用这一个对象。
         * v1 升级上来的行 `favorite_at_ms` 为空，退回解析老的文本列（与 [favorites] 同一套规则）。
         */
        readableDatabase.rawQuery(
            "SELECT id, name, category, density, times, emoji, favorite, favorite_at_ms, favorite_at " +
                "FROM dishes ORDER BY id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val favorite = cursor.getInt(6) == 1
                val millis = when {
                    !favorite -> 0L
                    !cursor.isNull(7) -> cursor.getLong(7)
                    else -> Units.parseLegacyDateTime(cursor.getString(8) ?: "")
                }
                list.add(
                    Food(
                        id = cursor.getLong(0).toString(),
                        name = cursor.getString(1),
                        category = cursor.getString(2),
                        density = cursor.getDouble(3),
                        times = cursor.getInt(4),
                        img = cursor.getString(5),
                        favorite = favorite,
                        favoriteAtMs = millis,
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

    /**
     * 从服务器导入菜品库（按名称去重），返回新增数量。
     *
     * ## `times` 也要带进来
     *
     * 以前这里只导 名称/分类/密度，`times` 一律落成 0 —— 于是"这道菜吃过几次"永远显示 0，
     * 站点上明明已经累计过。现在把它一起带进来。
     *
     * 登录之后 **食用次数以云端（按账号）为准**（见 [AppCore.syncCloudData]），
     * 它会先上报本地的、再拿云端的覆盖本地；所以这里带的 `times` 只在
     * "未登录、把服务器当作共用菜品库" 时起作用，两者不冲突。
     */
    fun importFoods(foods: List<Food>): Int {
        val existing = dishes().associateBy { it.name }.toMutableMap()
        var added = 0
        foods.forEach { food ->
            val emoji = if (food.img.endsWith(".svg")) State.emoji[food.img] ?: "🍽" else food.img
            val local = existing[food.name]
            if (local == null) {
                addDish(food.name, food.category, food.density, emoji)
                added++
                // 新插入的行拿到自增 id，回查一次好把 times 也补上
                val inserted = dishes().lastOrNull { it.name == food.name }
                if (inserted != null && food.times > 0) {
                    writableDatabase.execSQL(
                        "UPDATE dishes SET times = ? WHERE id = ?",
                        arrayOf(food.times, inserted.id),
                    )
                }
            } else if (food.times > local.times) {
                // 已有这道菜：只把"更大的次数"带过来（不覆盖用户本地更多的那个）
                writableDatabase.execSQL(
                    "UPDATE dishes SET times = ? WHERE id = ?",
                    arrayOf(food.times, local.id),
                )
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
        replaceMenuItems(menuId.toString(), dishIds)
        return menuId
    }

    /**
     * 改写一份菜单：换名字 + **整体替换**它的菜品列表。
     *
     * 为什么是整体替换而不是逐条增删：菜单编辑页交回来的就是"最终这份菜单包含哪些菜"，
     * 逐条 diff 既没必要（一份菜单就几道菜）又容易留下残留行。
     */
    fun updateMenu(menuId: String, name: String, dishIds: List<String>) {
        writableDatabase.execSQL(
            "UPDATE menus SET name = ? WHERE id = ?",
            arrayOf(name, menuId),
        )
        writableDatabase.delete("menu_items", "menu_id = ?", arrayOf(menuId))
        replaceMenuItems(menuId, dishIds)
    }

    private fun replaceMenuItems(menuId: String, dishIds: List<String>) {
        dishIds.forEachIndexed { position, dishId ->
            writableDatabase.insert("menu_items", null, ContentValues().apply {
                put("menu_id", menuId.toLongOrNull() ?: 0L)
                put("dish_id", dishId.toLongOrNull() ?: 0L)
                put("position", position)
            })
        }
    }

    fun menu(id: String): MenuDef? =
        menus().firstOrNull { it.id == id }

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

    /** 给一条用餐记录改名（用餐结束后用户可以自定义这一餐叫什么）。 */
    fun renameMeal(mealId: Long, name: String) {
        writableDatabase.execSQL(
            "UPDATE meals SET menu_name = ? WHERE id = ?",
            arrayOf(name, mealId.toString()),
        )
    }

    /**
     * 保存「用餐记录详情页」的修改：名称、口数、重量、热量、时长。
     *
     * 时长没有自己的列 —— 它在库里就是 `ended_at - started_at`（见 [meals]）。
     * 所以改时长要**挪结束时刻**（保持开始时刻不动）：这样"修改数据"不会把这一餐
     * 搬到别的时间点上，记录列表里的时间也还是原来那个。
     */
    fun updateRecordFields(
        mealId: Long,
        name: String,
        bites: Int,
        weight: Double,
        energy: Double,
        minutes: Int,
    ) {
        var startedAt = 0L
        var endedAt = 0L
        readableDatabase.rawQuery(
            "SELECT started_at, ended_at FROM meals WHERE id = ?", arrayOf(mealId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                startedAt = cursor.getLong(0)
                endedAt = cursor.getLong(1)
            }
        }
        if (endedAt <= 0L) return

        // 开始时刻 = 结束时刻 - 时长；时长为 0 时给 1 分钟，免得算出"这一餐 0 分钟"。
        // startedAt 为 0 的坏行保持原样，别把它写成负数时间。
        val newStart = (endedAt - minutes.coerceAtLeast(1) * 60_000L).takeIf { it > 0L } ?: startedAt
        writableDatabase.execSQL(
            "UPDATE meals SET menu_name = ?, bites = ?, weight = ?, energy = ?, started_at = ? WHERE id = ?",
            arrayOf(name, bites, weight, energy, newStart, mealId.toString()),
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

    /* ------------------------------------------------- 云端同步（记录 / 次数） */

    /**
     * 本地全部用餐记录 → 云端形态（含每一口明细），用于上报服务器。
     *
     * 用**结束时刻**当 `key`：它天然唯一（两顿饭不可能在同一毫秒结束），
     * 而且两端算出来一样，服务器据此去重。
     */
    fun cloudRecords(): List<CloudRecord> {
        val list = mutableListOf<CloudRecord>()
        readableDatabase.rawQuery(
            "SELECT id, menu_name, started_at, ended_at FROM meals ORDER BY ended_at",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val endedAt = cursor.getLong(3)
                list.add(
                    CloudRecord(
                        key = endedAt.toString(),
                        name = cursor.getString(1),
                        startedAt = cursor.getLong(2),
                        endedAt = endedAt,
                        bites = bites(id),
                    )
                )
            }
        }
        return list
    }

    /**
     * 用云端那份**整体覆盖**本地用餐记录（登录时调用）。
     *
     * 需求是明确的："登录时自动覆盖云端数据到本地"。覆盖而不是合并，理由是登录意味着
     * "我现在要看的是这个账号的数据"，把本机未登录时攒的那份混进来会让人分不清哪些是自己的。
     *
     * 覆盖前先把本地这份**上报**给服务器（见 `AppCore.syncCloudData`），所以不会被丢掉。
     */
    fun replaceRecords(records: List<CloudRecord>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("meals", null, null)
            writableDatabase.delete("bites", null, null)
            records.forEach { record ->
                val mealId = writableDatabase.insert("meals", null, ContentValues().apply {
                    put("menu_name", record.name)
                    put("started_at", record.startedAt)
                    put("ended_at", record.endedAt)
                    put("bites", record.bites.size)
                    put("weight", record.bites.sumOf { it.weight })
                    put("energy", record.bites.sumOf { it.energy })
                })
                record.bites.forEach { bite ->
                    writableDatabase.insert("bites", null, ContentValues().apply {
                        put("meal_id", mealId)
                        put("dish", bite.dish)
                        put("weight", bite.weight)
                        put("energy", bite.energy)
                        put("at", bite.at)
                    })
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 本地「菜名 → 食用次数」，用于上报服务器。 */
    fun usages(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT name, times FROM dishes WHERE times > 0", null).use { cursor ->
            while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getInt(1)
        }
        return map
    }

    /**
     * 用云端那份覆盖本地食用次数（按**菜名**匹配，不是 id —— 两套 id 体系不同，理由见 README）。
     */
    fun applyUsages(usages: Map<String, Int>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE dishes SET times = 0")
            usages.forEach { (name, times) ->
                if (times > 0) {
                    writableDatabase.execSQL(
                        "UPDATE dishes SET times = ? WHERE name = ?",
                        arrayOf(times, name),
                    )
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
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
     * 统计图的数据，**按当前的统计图设置现算**。
     *
     * 关键点是它必须接受这些参数，而不是自己去猜：
     * - [xAxis]：「用餐完成时间」用 `ended_at`、「用餐开始时间」用 `started_at`、
     *   「记录序号」不看时间（就是第几餐）；
     * - [yAxis]：「摄入能量 / 摄入重量 / 记录口数」三选一；
     * - [range]：「全部 / 近 7 次 / 近 5 次」—— **先按范围裁剪、再算刻度**，
     *   否则近 7 次的图会沿用全部数据的刻度，柱子被压扁在底部；
     * - [count]：`近 N 次` 里的 N（`null` = 全部）。
     *
     * 数值一律先换算到**当前显示单位**再交出去：数值、刻度、轴标签必须一起换，
     * 否则选了 kcal 之后图上还写着「摄入能量/kJ」。
     */
    fun chartData(xAxis: String, yAxis: String, count: Int? = null): ChartData {
        data class Row(val endedAt: Long, val startedAt: Long, val energy: Double, val weight: Double, val bites: Int)

        val rows = mutableListOf<Row>()
        readableDatabase.rawQuery(
            "SELECT ended_at, started_at, energy, weight, bites FROM meals ORDER BY ended_at",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    Row(
                        endedAt = cursor.getLong(0),
                        startedAt = cursor.getLong(1),
                        energy = Units.energyValue(cursor.getDouble(2)),
                        weight = Units.weightValue(cursor.getDouble(3)),
                        bites = cursor.getInt(4),
                    )
                )
            }
        }
        // 近 N 次：按结束时间取最后 N 条（ORDER BY ended_at，所以 takeLast 就是最近 N 餐）
        val shown = if (count != null) rows.takeLast(count) else rows

        val points = shown.mapIndexed { index, row ->
            val y = when (yAxis) {
                "摄入重量" -> row.weight.toInt()
                "记录口数" -> row.bites
                else -> row.energy.toInt()
            }
            val x = when (xAxis) {
                "用餐开始时间" -> xLabelOf(row.startedAt)
                // 序号就是"第几餐"：列表里那条记录也是同一个序号，方便对上
                "记录序号" -> "#${index + 1}"
                else -> xLabelOf(row.endedAt)
            }
            ChartPoint(x, y)
        }

        val max = points.maxOfOrNull { it.y } ?: 0
        val ticks = (0..6).map { it * tickStep(max) / 6 }
        // 点多的时候抽稀标签：最多画 8 个，最后一个由绘制侧补上
        val labelEvery = maxOf(1, ceil(points.size / 8.0).toInt())

        return ChartData(
            xLabel = if (xAxis == "记录序号") "记录序号" else xAxis,
            yLabel = when (yAxis) {
                "摄入重量" -> Units.weightAxisLabel()
                "记录口数" -> "记录口数/口"
                else -> Units.energyAxisLabel()
            },
            yTicks = ticks,
            // 先抽稀：每 labelEvery 个取一个
            xTicks = points.filterIndexed { i, _ -> i % labelEvery == 0 }.map { it.x },
            points = points,
            labelEvery = labelEvery,
        )
    }

    /**
     * x 轴的一处时间标签。
     *
     * 用 [Units.dateTime]（受「设置 → 杂项」里的**年 / 秒**两个开关控制），
     * 而不是只画日期：同一天可能吃好几餐，只写「10月4日」会得到几个一模一样的刻度。
     */
    private fun xLabelOf(millis: Long): String =
        if (millis > 0L) Units.dateTime(millis) else "—"

    /**
     * y 轴刻度步长：把最大值凑成 6 段、每段再"取整"到 1/2/5×10ⁿ，
     * 这样刻度值是 100、200、400 这种好读的数，而不是 137、274 这种。
     */
    private fun tickStep(max: Int): Int {
        if (max <= 0) return 1
        val raw = max / 6.0
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        val nice = when {
            n <= 1.0 -> 1.0
            n <= 2.0 -> 2.0
            n <= 5.0 -> 5.0
            else -> 10.0
        }
        return maxOf(1, (nice * mag).roundToInt()) * 6
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
