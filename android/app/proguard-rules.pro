# release 构建（R8）规则。
# 本应用不依赖反射：JSON 全是手写解析（Data.kt），本地存储用 SQLite（Store.kt），
# 所以默认规则就够。这里只保留必要的说明性条目。

# 保留行号，崩溃栈才有意义
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
