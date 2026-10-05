package com.equimeal.gramo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.Account
import com.equimeal.gramo.AppCore
import com.equimeal.gramo.State
import com.equimeal.gramo.Store

/*
 * 账户页（设置 → 账户设置 / p.18）。
 *
 * 设计上只有两个状态，用一个页面装下：
 * 1. **未登录** → 显示「登录」/「注册」表单（用户名 + 密码，注册时多一个昵称）；
 * 2. **已登录** → 显示账号信息 + 退出登录 + 进账号设置（昵称/头像/关于）。
 *
 * 关键约定：**登录是可选的**。未登录也能完整使用（菜品、菜单、用餐记录都在本地库里），
 * 所以这一页一定给「暂不登录，继续离线使用」这条退路 —— 否则等于强制注册，
 * 而这是一个离线优先的应用。
 */

/** 未登录时的登录 / 注册表单。 */
@Composable
fun AccountLoginScreen(a: AccountHost) {
    var register by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var localMessage by remember { mutableStateOf<String?>(null) }

    // 服务器返回的失败原因（"用户名已注册"等）显示在表单下方
    val message = localMessage ?: State.accountMessage

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    if (register) "注册智味勺账号" else "登录智味勺账号",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "登录后用餐记录按账号保存，换账号不会串数据",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; localMessage = null },
            label = { Text("用户名") },
            placeholder = { Text("2~20 位字母、数字、下划线或汉字") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        if (register) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it; localMessage = null },
                label = { Text("昵称（可留空，默认与用户名相同）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; localMessage = null },
            label = { Text("密码") },
            placeholder = { Text("至少 6 位") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    val user = username.trim()
                    if (user.isEmpty()) {
                        localMessage = "请填写用户名"
                        return@Button
                    }
                    if (password.length < 6) {
                        localMessage = "密码至少 6 位"
                        return@Button
                    }
                    localMessage = null
                    // 昵称留空时由服务器默认成用户名；填了就按填的来
                    a.authenticate(user, password, nickname, register) { ok ->
                        if (ok) password = ""
                    }
                },
                enabled = !State.accountBusy,
                modifier = Modifier.weight(1f),
            ) {
                if (State.accountBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (register) "注册并登录" else "登录")
            }
            OutlinedButton(
                onClick = {
                    register = !register
                    localMessage = null
                    State.accountMessage = null
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (register) "已有账号？去登录" else "没有账号？去注册") }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { a.skipLogin() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("暂不登录，继续离线使用") }

        Spacer(Modifier.height(18.dp))

        Text(
            "服务器：${State.server}\n" +
                "密码在服务器上以加盐哈希保存，App 里只存登录令牌、不存密码。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (State.account == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "当前使用本地数据：${Store.forAccount(null)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 已登录时的账号信息 + 退出登录。 */
@Composable
fun AccountProfileScreen(a: AccountHost, account: Account) {
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(account.displayName) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(18.dp))
            Column {
                Text(account.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "用户名：${account.username}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        if (renaming) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        a.renameAccount(newName) { renaming = false }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存昵称") }
                OutlinedButton(
                    onClick = { renaming = false; newName = account.displayName },
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
            }
        } else {
            Button(
                onClick = { newName = account.displayName; renaming = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("修改昵称") }
        }

        Spacer(Modifier.height(18.dp))

        InfoLine("本机数据文件", Store.forAccount(account.username))
        InfoLine("登录令牌", "已保存（App 不保存密码）")

        Spacer(Modifier.height(18.dp))

        OutlinedButton(
            onClick = { a.logout() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("退出登录") }

        Spacer(Modifier.height(8.dp))
        Text(
            "退出后本机仍保留这个账号的数据（${Store.forAccount(account.username)}），" +
                "下次登录还能看到；退出只是回到「未登录」状态。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一行"标题 —— 值"的信息行。 */
@Composable
private fun InfoLine(title: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * 账户页需要宿主提供的能力。
 *
 * 做成接口而不是直接调 [AppCore]，是为了让这一页能同时被"设置里的账户设置页"和
 * 将来的"首次启动引导页"复用 —— 后者需要翻页/收尾的额外动作，而前者不需要。
 */
interface AccountHost {
    /** 注册（register=true）或登录。回调参数 null = 成功。 */
    fun authenticate(
        username: String,
        password: String,
        nickname: String,
        register: Boolean,
        onDone: (Boolean) -> Unit,
    )

    fun logout()

    fun renameAccount(name: String, onDone: () -> Unit)

    /** 「暂不登录，继续离线使用」：未登录也能用整个应用。 */
    fun skipLogin()
}
