"""示例数据：食物库、收藏夹、菜单、用餐记录、设备。"""

from __future__ import annotations

# 食物库（能量密度单位 kJ/g，食用次数用于「按食用次数排序」）
FOODS = [
    {"id": "f01", "name": "海鲜比萨", "category": "肉类", "density": 2.86, "times": 26, "img": "pizza.svg"},
    {"id": "f02", "name": "蛋糕", "category": "水果", "density": 4.11, "times": 14, "img": "cake.svg"},
    {"id": "f03", "name": "巨无霸汉堡", "category": "肉类", "density": 3.57, "times": 9, "img": "burger.svg"},
    {"id": "f04", "name": "孜然羊肉", "category": "肉类", "density": 8.20, "times": 12, "img": "lamb.svg"},
    {"id": "f05", "name": "汽水", "category": "水果", "density": 1.80, "times": 21, "img": "soda.svg"},
    {"id": "f06", "name": "家常凉菜", "category": "蔬菜", "density": 1.05, "times": 17, "img": "salad.svg"},
    {"id": "f07", "name": "哈密瓜", "category": "水果", "density": 1.34, "times": 11, "img": "melon.svg"},
    {"id": "f08", "name": "小米粥", "category": "蔬菜", "density": 1.90, "times": 8, "img": "congee.svg"},
    {"id": "f09", "name": "清炒时蔬", "category": "蔬菜", "density": 0.96, "times": 15, "img": "greens.svg"},
    {"id": "f10", "name": "红烧肉", "category": "肉类", "density": 12.60, "times": 7, "img": "pork.svg"},
    {"id": "f11", "name": "牛肉面", "category": "肉类", "density": 3.10, "times": 19, "img": "noodle.svg"},
    {"id": "f12", "name": "苹果", "category": "水果", "density": 2.18, "times": 23, "img": "apple.svg"},
]

FOOD_BY_ID = {food["id"]: food for food in FOODS}

CATEGORIES = ["全部", "肉类", "蔬菜", "水果"]

# 收藏夹
FOLDERS = [
    {"id": "d1", "name": "收藏夹 1", "foods": ["f01", "f02", "f03"]},
    {"id": "d2", "name": "收藏夹 2", "foods": ["f05", "f06"]},
    {"id": "d3", "name": "常吃肉类", "foods": ["f04", "f10", "f11"]},
]

# 收藏时间（展示用，页码 10）
FAVORITE_TIMES = {
    "f01": "2026年8月25日 12:02",
    "f02": "2026年8月24日 12:02",
    "f03": "2026年8月23日 12:02",
    "f04": "2026年8月22日 12:02",
    "f05": "2026年8月21日 12:02",
    "f06": "2026年8月20日 12:02",
}

# 菜单（设置 → 菜单）
MENUS = [
    {"id": "m1", "name": "菜单1", "foods": ["f04", "f05", "f06", "f07"]},
    {"id": "m2", "name": "减脂餐", "foods": ["f04", "f06", "f09"]},
]

# 用餐记录（页码 11）
RECORDS = [
    {
        "no": 1,
        "menu": "菜单1",
        "foods": ["f04", "f05", "f06", "f07"],
        "bites": 25,
        "weight": 400,
        "energy": 5500,
        "start": "2026年8月26日 11:21",
        "end": "2026年8月26日 12:08",
    },
    {
        "no": 2,
        "menu": "张三的晚餐",
        "foods": ["f04", "f05", "f06", "f07"],
        "bites": 25,
        "weight": 400,
        "energy": 5500,
        "start": "2026年8月26日 18:51",
        "end": "2026年8月26日 19:29",
    },
    {
        "no": 3,
        "menu": "菜单2",
        "foods": ["f04", "f05", "f06"],
        "bites": 25,
        "weight": 400,
        "energy": 5500,
        "start": "2026年8月27日 11:21",
        "end": "2026年8月27日 12:08",
    },
]

# 用餐记录 → 统计数据（页码 12）
STATS = {
    "总用餐时间": "4小时16分钟",
    "总记录口数": "104",
    "总摄入重量": "1992 g",
    "总摄入能量": "4356 kJ",
    "总餐数": "4",
}

# 统计折线图数据（页码 13）
CHART = {
    "x_label": "用餐完成时间",
    "y_label": "摄入能量/kJ",
    "y_ticks": [0, 200, 400, 600, 800, 1000, 1200],
    "x_ticks": ["2026/8/21", "2026/8/22", "2026/8/23", "2026/8/24", "2026/8/25", "2026/8/26", "2026/8/27", "2026/8/28", "2026/8/29"],
    "points": [
        {"x": "2026/8/21", "y": 355},
        {"x": "2026/8/22", "y": 402},
        {"x": "2026/8/23", "y": 428},
        {"x": "2026/8/24", "y": 432},
        {"x": "2026/8/25", "y": 606},
        {"x": "2026/8/26", "y": 940},
        {"x": "2026/8/27", "y": 1105},
        {"x": "2026/8/28", "y": 1168},
        {"x": "2026/8/29", "y": 1200},
    ],
}

# 设备（连接智味勺 / 设备管理）
# nearby: 是否会出现在「附近的智味勺」列表中（设计稿 p.15 / p.16）
# picker: 是否出现在「连接智味勺 · 重新选择」弹窗里（设计稿 p.05 只列出 3 台）
DEVICES = [
    {"id": "s1", "name": "张三的智味勺", "battery": 60, "saved": True, "connected": True,
     "nearby": True, "picker": True},
    {"id": "s2", "name": "李四的智味勺", "battery": 46, "saved": True, "connected": False,
     "nearby": True, "picker": True},
    {"id": "s3", "name": "王五的智味勺", "battery": 60, "saved": False, "connected": False,
     "nearby": True, "picker": True},
    {"id": "s4", "name": "Sccrea的智味勺", "battery": 60, "saved": False, "connected": False,
     "nearby": True, "picker": False},
    {"id": "s5", "name": "张三妈妈的智味勺", "battery": 46, "saved": True, "connected": False,
     "nearby": False, "picker": False},
]

USER = {"name": "张三", "avatar": "avatar.svg"}

# 用餐中演示数据（页码 7）
LIVE_DEMO = {
    "duration_minutes": 18,
    "food": "小米粥",
    "spoon_weight": 48,
    "spoon_energy": 221,
    "bites": 15,
    "total_weight": 198,
    "total_energy": 912,
}

# 用餐结果演示数据（页码 8/9）
RESULT_DEMO = {
    "savedNo": 4,
    "duration": "49 分钟",
    "bites": 25,
    "weight": "359 g",
    "energy": "1653 kJ",
    "avgWeight": "14.4 g",
    "avgEnergy": "66.1 kJ",
}
