# 发布版安装包

这里放的是**正式发布的 APK**（`build/` 目录里的中间产物仍然被忽略，因为那些每次构建都会变）。

| 文件 | 版本 | 包名 | 大小 | 构建方式 |
| --- | --- | --- | --- | --- |
| `EquiMeal-0.1.0.apk` | 0.1.0（versionCode 4） | `com.equimeal` | 约 1.3 MB | `gradlew :app:assembleRelease`（R8 压缩 + 资源收缩） |

## 安装

```powershell
adb install -r release\EquiMeal-0.1.0.apk
```

或者直接把 APK 传到手机上点安装（需要在系统设置里允许"安装未知应用"）。

## 签名说明

用的是仓库里的 `android/debug.keystore`（别名 `androiddebugkey`、口令 `android`）。
它只是**调试密钥**，公开约定、不构成机密 —— 这样做是为了让 `assembleDebug` /
`assembleRelease` 在任何一台克隆了仓库的机器上都能直接出包，也便于 `adb install -r`
就地覆盖升级。

> **上架应用商店前必须换成自己的正式签名密钥**：调试密钥是公开的，任何人都能用它签出
> 一个"看起来是 EquiMeal"的包；而且换密钥后已安装的版本无法直接覆盖升级。

## 包名变更（0.0.x → 0.1.0）

0.1.0 把包名从 `com.smartspoon.l2` 改成了 `com.equimeal`，应用名也从「智味勺」改成 **EquiMeal**
（「智味勺」现在专指那把 BLE 硬件勺子）。

这是一次**不兼容的身份变更**：

- 系统把新旧两个包当成**两个不同的应用**，新包**不会覆盖**旧包，而是并存两个图标；
- 旧应用的本地数据（菜品 / 菜单 / 用餐记录）**不会**迁移过来；
- 想彻底换掉的话，先卸载旧包：

```powershell
adb uninstall com.smartspoon.l2
```

登录同一个账号即可从云端取回按账号保存的用餐记录与食用次数（见根目录 README 的「数据归属」）。
