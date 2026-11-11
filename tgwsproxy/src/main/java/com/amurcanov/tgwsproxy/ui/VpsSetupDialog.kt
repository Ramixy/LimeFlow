package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amurcanov.tgwsproxy.VpsInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Свой сервер": IP, SSH login and password in, a ready Cloudflare-free domain
 * out. Everything else (nginx, certificate, sslip.io name) is done on the VPS
 * by assets/setup-vps.sh.
 */
@Composable
fun VpsSetupDialog(
    onDismiss: () -> Unit,
    onInstalled: (domain: String, plain: Boolean?) -> Unit,
    coverSni: String = "",
    savedHost: String = "",
    savedUser: String = "root",
    onRemember: (host: String, user: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(savedHost) }
    var sshPort by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf(savedUser.ifBlank { "root" }) }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    val log = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var resultDomain by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Свой сервер", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "LimeFlow сам подключится к вашему VPS по SSH и настроит его вместо Cloudflare. " +
                            "Нужен Debian или Ubuntu. Пароль используется один раз и не сохраняется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    @Composable
                    fun field(
                        label: String,
                        value: String,
                        onChange: (String) -> Unit,
                        keyboard: KeyboardType = KeyboardType.Text,
                        secret: Boolean = false,
                        modifier: Modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = onChange,
                            label = { Text(label) },
                            singleLine = true,
                            enabled = !running,
                            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                            visualTransformation = if (secret) PasswordVisualTransformation()
                            else androidx.compose.ui.text.input.VisualTransformation.None,
                            shape = RoundedCornerShape(16.dp),
                            modifier = modifier,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field("IP сервера", host, { host = it.trim() }, KeyboardType.Uri, modifier = Modifier.weight(1f))
                        field("Порт", sshPort, { v -> sshPort = v.filter { it.isDigit() }.take(5) },
                            KeyboardType.Number, modifier = Modifier.width(96.dp))
                    }
                    field("Логин", user, { user = it.trim() })
                    field("Пароль", password, { password = it }, KeyboardType.Password, secret = true)

                    TextButton(onClick = { showAdvanced = !showAdvanced }, enabled = !running) {
                        Text(if (showAdvanced) "Скрыть доп. настройки" else "Свой домен (необязательно)")
                    }
                    if (showAdvanced) {
                        Text(
                            "Без домена используется бесплатный <ip>.sslip.io. Для своего домена " +
                                "заранее направьте kws1…kws5 и kws203 на IP сервера. С почтой будет " +
                                "выпущен сертификат Let's Encrypt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        field("Домен", domain, { domain = it.trim() }, KeyboardType.Uri)
                        field("Почта для Let's Encrypt", email, { email = it.trim() }, KeyboardType.Email)
                    }

                    if (log.isNotEmpty()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                        ) {
                            items(log) { line ->
                                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                            }
                        }
                    }
                    resultDomain?.let {
                        Text(
                            "Готово! Прокси переключён на $it",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    error?.let {
                        Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                ) {
                    TextButton(onClick = { job?.cancel(); onDismiss() }) {
                        Text(if (running) "Отменить" else "Закрыть")
                    }
                    Button(
                        enabled = !running && host.isNotBlank() && user.isNotBlank() && password.isNotEmpty() &&
                            (sshPort.toIntOrNull() ?: 0) in 1..65535,
                        onClick = {
                            log.clear()
                            error = null
                            resultDomain = null
                            val request = VpsInstaller.Request(
                                host = host,
                                port = sshPort.toInt(),
                                user = user,
                                password = password,
                                domain = domain,
                                email = email,
                            )
                            running = true
                            job = scope.launch {
                                try { when (val r = VpsInstaller.install(context, request) { line ->
                                    scope.launch { log.add(line) }
                                }) {
                                    is VpsInstaller.Result.Ok -> {
                                        password = ""
                                        onRemember(request.host, request.user)
                                        log.add("Проверяем, какой режим проходит в этой сети…")
                                        val plain = com.amurcanov.tgwsproxy.ServerProbe.detectPlain(r.domain, coverSni)
                                        log.add(when (plain) {
                                            false -> "TLS проходит — обычный режим"
                                            true -> "TLS режется, HTTP проходит — включён режим «Без TLS»"
                                            null -> "Сервер с телефона пока не отвечает — режим не менялся"
                                        })
                                        resultDomain = r.domain
                                        onInstalled(r.domain, plain)
                                    }
                                    is VpsInstaller.Result.Failed -> error = r.message
                                } } finally {
                                    running = false
                                }
                            }
                        },
                    ) {
                        if (running) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Установить")
                        }
                    }
                }
            }
        }
    }
}

/** Results of [com.amurcanov.tgwsproxy.ServerProbe], line by line. */
@Composable
fun ServerProbeDialog(domain: String, coverSni: String, onDismiss: () -> Unit) {
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(true) }
    LaunchedEffect(domain, coverSni) {
        try {
            com.amurcanov.tgwsproxy.ServerProbe.run(domain, coverSni) { line ->
                kotlinx.coroutines.MainScope().launch { lines.add(line) }
            }
        } finally {
            running = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Проверка сервера") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (running) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
    )
}