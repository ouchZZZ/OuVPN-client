package com.example.myvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.wireguard.config.Config
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val scope = MainScope()
    private enum class Screen(val title: String) { HOME("Пульт"), NODES("Ноды"), SUBS("Подписки"), APPS("Мимо VPN") }
    private val settings by lazy { getSharedPreferences("ouvpn-settings", MODE_PRIVATE) }
    private var screen = Screen.HOME
    private var subscriptions = emptyList<SavedSubscription>()
    private var installed: List<Pair<String, String>>? = null
    private var loadingApps = false
    private var pending: MyVpnService.Selection? = null
    private var busy = false
    private var loaded = false
    private var message = ""
    private lateinit var page: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var scroll: ScrollView

    private var wgConfig: Config? = null
    private var wgLoadFailed = false
    private val reports = mutableMapOf<EgressMode, RouteReport>()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selection = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && selection != null) startTunnel(selection)
        else show("Не получено разрешение Android на VPN.")
    }

    private val importConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val config = withContext(Dispatchers.IO) {
                val stream = contentResolver.openInputStream(uri) ?: throw IOException()
                stream.use {
                    val bytes = ByteArray(65537)
                    var total = 0
                    while (total < bytes.size) {
                        val n = it.read(bytes, total, bytes.size - total)
                        if (n < 0) break
                        total += n
                    }
                    require(total in 1..65536)
                    Config.parse(bytes.inputStream(0, total))
                }
            }
            if (config.peers.singleOrNull()?.endpoint?.isPresent != true || config.getInterface().addresses.isEmpty()) {
                show("Нужен клиентский WireGuard-конфиг с адресом и одним peer с Endpoint.")
            } else {
                val previous = withContext(Dispatchers.IO) {
                    SavedVpnState.saveConfig(applicationContext, config)
                    runCatching { SavedVpnState.loadReport(applicationContext, config) }.getOrNull()
                }
                wgConfig = config
                wgLoadFailed = false
                reports.remove(EgressMode.HOME)
                if (previous != null) reports[EgressMode.HOME] = previous
                show("Конфиг WireGuard сохранён на устройстве.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = column().apply { setPadding(20.dp(), 10.dp(), 20.dp(), 20.dp()) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        nav = LinearLayout(this).apply { setBackgroundColor(getColor(R.color.ou_panel)); setPadding(6.dp(), 0, 6.dp(), 0) }
        setContentView(column().apply {
            setBackgroundColor(getColor(R.color.ou_base)); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); addView(nav)
        })
        drawNav()
        scope.launch { MyVpnService.status.collect { if (screen == Screen.HOME || screen == Screen.NODES) render() } }
        scope.launch {
            // Ignore any replay from a previous activity; history was already written to disk.
            var previous = MyVpnService.report.value
            MyVpnService.report.collect { latest ->
                if (latest == null || latest === previous) return@collect
                previous = latest
                reports[latest.egress] = latest
                if (screen == Screen.HOME || screen == Screen.NODES) render()
            }
        }
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                val config = runCatching { SavedVpnState.loadConfig(applicationContext) }
                val histories = listOfNotNull(
                    runCatching { SavedVpnState.loadReport(applicationContext, null) }.getOrNull(),
                    config.getOrNull()?.let { runCatching { SavedVpnState.loadReport(applicationContext, it) }.getOrNull() },
                )
                config to histories
            }
            wgConfig = restored.first.getOrNull()
            wgLoadFailed = restored.first.isFailure
            if (restored.first.isFailure) message = "Не удалось прочитать сохранённый конфиг WireGuard. Можно импортировать его заново."
            restored.second.forEach { reports.putIfAbsent(it.egress, it) }
            SubscriptionRepository.changed.collect {
                try {
                    subscriptions = withContext(Dispatchers.IO) { SubscriptionRepository.list(applicationContext) }
                    loaded = true
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { message = "Не удалось прочитать подписки. Данные не изменены." }
                if (screen != Screen.APPS) render()
            }
        }
        runCatching { SubscriptionRefreshJob.schedule(this) }
        render()
    }

    private fun drawNav() {
        nav.removeAllViews()
        Screen.entries.forEach { tab ->
            nav.addView(label(tab.title, 13f, if (tab == screen) R.color.ou_mint else R.color.ou_dim, tab == screen).apply {
                gravity = Gravity.CENTER; minHeight = 52.dp(); isSelected = tab == screen; isFocusable = true
                setOnClickListener {
                    if (screen != tab) { screen = tab; message = ""; drawNav(); if (tab == Screen.APPS) loadApps(); render(true) }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun render(reset: Boolean = false) {
        if (!::page.isInitialized || isDestroyed) return
        val offset = if (reset) 0 else scroll.scrollY
        page.removeAllViews()
        val title = when (screen) {
            Screen.HOME -> (if (egress() == EgressMode.HOME) "ДОМАШНИЙ ТУННЕЛЬ" else "VPN ЧЕРЕЗ НОДУ") to getString(R.string.app_name)
            Screen.NODES -> "МАРШРУТЫ" to "Ноды"
            Screen.SUBS -> "ИСТОЧНИКИ" to "Подписки"
            Screen.APPS -> "РАЗДЕЛЕНИЕ ТРАФИКА" to "Приложения вне VPN"
        }
        page.addView(label(title.first, 11f, R.color.ou_mint, true).apply { letterSpacing = 0.16f })
        page.addView(label(title.second, 29f, bold = true))
        if (message.isNotBlank()) page.addView(label(message, 14f, R.color.ou_warning))
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.NODES -> renderNodes()
            Screen.SUBS -> renderSubscriptions()
            Screen.APPS -> renderApps()
        }
        scroll.post { if (!isDestroyed) scroll.scrollTo(0, offset) }
    }

    private fun renderHome() {
        val state = MyVpnService.status.value
        val report = reports[egress()] ?: RouteReport(egress = egress())
        val ready = loaded && !busy && !blockedWireGuard() && (egress() == EgressMode.HOME ||
            subscriptions.any { sub -> sub.nodes.any(SingBoxConfig::supportsNodeTunnel) })
        val canConnect = ready && (egress() == EgressMode.HOME || Build.VERSION.SDK_INT >= 29)
        val mode = when (state.mode) {
            MyVpnService.Mode.AUTO -> "АВТОРЕЖИМ"
            MyVpnService.Mode.MANUAL -> "РУЧНОЙ МАРШРУТ"
            else -> if (state.busy) "ПРОВЕРКА" else "СТАТУС"
        }
        page.addView(panel().apply {
            addView(label(mode, 11f, R.color.ou_mint, true).apply { letterSpacing = 0.12f })
            addView(label(state.text, 21f, bold = true))
            if (state.busy) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
            val speeds = if (state.egress == EgressMode.HOME) report.home else report.outer
            state.routeKey?.let { speeds[it] }?.let { addView(label("Последний замер · ${format(it)}", 13f, R.color.ou_dim)) }
        })
        if (state.active || state.busy) page.addView(button(if (state.active) "Отключить" else "Остановить", primary = true) {
            stopService(Intent(this, MyVpnService::class.java))
        }) else page.addView(button("Подключиться", canConnect, primary = true) { connect(MyVpnService.Mode.AUTO) })
        if (state.active && state.mode == MyVpnService.Mode.MANUAL && !state.busy) {
            page.addView(button("Перейти в авторежим") { connect(MyVpnService.Mode.AUTO) })
        }
        page.addView(button("Проверить VPN", (state.active || ready) && !state.busy && !busy, click = ::fullCheck))
        page.addView(CheckBox(this).apply {
            text = "Домашний WireGuard поверх подписок"
            isChecked = homeEnabled()
            isEnabled = !state.active && !state.busy && !busy
            setOnCheckedChangeListener { _, checked ->
                settings.edit().putBoolean("homeEnabled", checked).apply()
                render()
            }
        })
        if (blockedWireGuard()) page.addView(label("Не удалось прочитать конфиг WireGuard. Импортируй его заново или выключи WireGuard.", 13f, R.color.ou_dim))
        else if (homeEnabled() && wgConfig == null) page.addView(label("Конфиг не загружен — используется режим через ноду.", 13f, R.color.ou_dim))
        if (egress() == EgressMode.NODE && Build.VERSION.SDK_INT < 29) page.addView(label("Подключение через ноду требует Android 10+. Замер скорости доступен отдельно.", 13f, R.color.ou_dim))
        page.addView(button(if (wgConfig == null) "Импортировать WireGuard .conf" else "Заменить WireGuard .conf", loaded && !state.active && !state.busy && !busy) {
            importConfig.launch(arrayOf("*/*"))
        })
        if (notificationsMissing()) page.addView(button("Включить статус в шторке") { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
        val nodes = subscriptions.flatMap { it.namedNodes() }
        RouteHistory.best(report, nodes, egress())?.let {
            page.addView(label("Лучший по проверке: ${it.node?.name ?: "WireGuard напрямую"}", 14f, R.color.ou_dim))
        }
    }

    private fun renderNodes() {
        val state = MyVpnService.status.value
        val metrics = reports[egress()] ?: RouteReport(egress = egress())
        val home = egress() == EgressMode.HOME
        val enabled = loaded && !busy && !state.busy && !blockedWireGuard() && (home || Build.VERSION.SDK_INT >= 29)
        val candidates = subscriptions.flatMap { it.namedNodes() }.distinctBy { it.link }.sortedBy { it.name }
        val scores = if (home) metrics.home else metrics.outer
        val ranked = RoutePolicy.ranked(candidates.mapNotNull { node -> scores[node.link]?.let { node to it } }.toMap())
        val nodes = ranked + (candidates - ranked.toSet())
        for (route in (if (home) listOf(HomeRoute()) else emptyList()) + nodes.map { HomeRoute(it) }) {
            val node = route.node
            val key = route.key
            val protocol = when (node?.protocol) { "vless" -> "VLESS / XUDP"; "hysteria2" -> "HYSTERIA 2"; else -> "БЕЗ НОДЫ" }
            page.addView(panel().apply {
                addView(label(if (state.routeKey == key) "$protocol · ВЫБРАН" else protocol, 11f, R.color.ou_mint, true))
                addView(label(node?.name ?: "WireGuard напрямую", 19f, bold = true))
                if (node != null) addView(label(when {
                    metrics.outer[key] != null -> "Нода: ${format(checkNotNull(metrics.outer[key]))}"
                    metrics.ping[key] != null -> "Нода: ответила за ${metrics.ping[key]} мс · нет замера скорости"
                    metrics.ping.containsKey(key) -> "Нода: не прошла проверку за 1,5 с"
                    metrics.outer.containsKey(key) -> "Нода: не удалось измерить скорость"
                    else -> "Нода: не проверена"
                }, 13f, R.color.ou_dim))
                if (home) {
                    val speed = metrics.home[key]
                    addView(label(if (speed != null) "Через дом: ${format(speed)}" else if (metrics.home.containsKey(key)) "Через дом: тест не прошёл" else "Через дом: не проверено", 13f, R.color.ou_dim))
                }
                val supported = home || (node != null && SingBoxConfig.supportsNodeTunnel(node))
                if (!supported) addView(label("Профиль ноды не поддерживается в режиме без WireGuard.", 13f, R.color.ou_dim))
                addView(button("Выбрать вручную", enabled && supported) { connect(MyVpnService.Mode.MANUAL, route) })
            })
        }
        if (nodes.isEmpty()) page.addView(label("Добавь подписку Happ, чтобы появились ноды.", 14f, R.color.ou_dim))
    }

    private fun renderSubscriptions() {
        page.addView(button("Добавить подписку Happ", loaded && !busy, primary = true, click = ::addSubscription))
        page.addView(button("Обновить подписки", !busy && subscriptions.isNotEmpty(), click = ::refreshSubscriptions))
        page.addView(label("Фоновое обновление — примерно раз в 6 часов, когда есть сеть.", 13f, R.color.ou_dim))
        for (sub in subscriptions) page.addView(panel().apply {
            addView(label(sub.label, 19f, bold = true)); addView(label("${sub.nodes.size} нод · Happ", 13f, R.color.ou_dim))
            addView(button("Удалить из приложения", !busy) {
                AlertDialog.Builder(this@MainActivity).setTitle("Удалить подписку?")
                    .setMessage("Это не освобождает слот у провайдера и не разрывает текущий VPN.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                        perform { withContext(Dispatchers.IO) { SubscriptionRepository.remove(applicationContext, sub.link) }; show("Подписка удалена.") }
                    }.show()
            })
        })
    }

    private fun loadApps() {
        if (installed != null || loadingApps) return
        loadingApps = true
        scope.launch {
            try {
                installed = withContext(Dispatchers.IO) {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                        .filterNot { it.first == packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase(Locale.getDefault()) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Не удалось загрузить список приложений." }
            finally { loadingApps = false }
            if (screen == Screen.APPS) render()
        }
    }

    private fun renderApps() {
        page.addView(label("Отмеченные приложения идут напрямую. Изменения применятся при следующем подключении.", 14f, R.color.ou_dim))
        if (loadingApps) page.addView(label("Загружаем приложения…"))
        val search = EditText(this).apply { hint = "Поиск приложения"; setSingleLine(true) }
        val list = column()
        page.addView(search); page.addView(list)
        fun fill(query: String) {
            list.removeAllViews()
            val selected = exclusions()
            installed.orEmpty().filter { it.first.contains(query, true) || it.second.contains(query, true) }.forEach { (id, name) ->
                list.addView(CheckBox(this).apply {
                    text = name; minHeight = 48.dp(); isChecked = id in selected
                    setOnCheckedChangeListener { _, checked ->
                        val values = exclusions().toMutableSet()
                        if (checked) values.add(id) else values.remove(id)
                        settings.edit().putStringSet("excluded", values).apply()
                    }
                })
            }
        }
        fill(""); search.doAfterTextChanged { fill(it?.toString().orEmpty().trim()) }
    }

    private fun addSubscription() {
        val form = column().apply { setPadding(20.dp(), 0, 20.dp(), 0) }
        val name = EditText(this).apply { hint = "Название (необязательно)"; setSingleLine(true) }
        val url = EditText(this).apply {
            hint = "HTTPS-ссылка подписки Happ"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO
        }
        val device = CheckBox(this).apply { text = "Зарегистрировать этот клиент как устройство"; isChecked = true }
        form.addView(name); form.addView(url); form.addView(device)
        AlertDialog.Builder(this).setTitle("Добавить подписку").setView(form).setNegativeButton("Отмена", null)
            .setPositiveButton("Загрузить") { _, _ ->
                val link = url.text.toString().trim()
                val title = name.text.toString().trim()
                val sendId = device.isChecked
                perform {
                    val count = withContext(Dispatchers.IO) { SubscriptionRepository.add(applicationContext, link, title, sendId) }
                    show("Подписка добавлена · $count нод")
                }
            }.show()
    }

    private fun refreshSubscriptions() = perform {
        show(withContext(Dispatchers.IO) { SubscriptionRepository.refresh(applicationContext) })
    }

    private fun fullCheck() {
        if (!MyVpnService.status.value.active) { connect(MyVpnService.Mode.TEST); return }
        AlertDialog.Builder(this).setTitle("Проверить VPN?")
            .setMessage("Проверка может временно приостановить трафик. После неё вернётся исходный маршрут.")
            .setNegativeButton("Отмена", null).setPositiveButton("Проверить") { _, _ ->
                startForegroundService(Intent(this, MyVpnService::class.java).setAction(MyVpnService.CHECK))
            }.show()
    }

    private fun exclusions() = settings.getStringSet("excluded", emptySet()).orEmpty().toSet()
    private fun homeEnabled() = settings.getBoolean("homeEnabled", false)
    private fun blockedWireGuard() = homeEnabled() && wgLoadFailed
    private fun egress() = EgressMode.resolve(homeEnabled(), wgConfig != null)

    private fun connect(mode: MyVpnService.Mode, route: HomeRoute? = null) {
        if (blockedWireGuard()) return
        val config = wgConfig.takeIf { egress() == EgressMode.HOME }
        if (config == null && mode != MyVpnService.Mode.TEST && Build.VERSION.SDK_INT < 29) return
        val selection = MyVpnService.Selection(config, mode, route, exclusions())
        val intent = if (mode == MyVpnService.Mode.TEST) null else VpnService.prepare(this)
        if (intent == null) startTunnel(selection) else {
            pending = selection
            vpnPermission.launch(intent)
        }
    }

    private fun startTunnel(selection: MyVpnService.Selection) {
        message = ""
        MyVpnService.selection = selection
        startForegroundService(Intent(this, MyVpnService::class.java))
        if (notificationsMissing() && !settings.getBoolean("notificationsAsked", false)) {
            settings.edit().putBoolean("notificationsAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationsMissing() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        render()
        scope.launch {
            try {
                action()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                // Exception strings can contain subscription URLs or imported key material.
                val detail = error.message.orEmpty()
                show(when {
                    error is SubscriptionFailure -> error.message.orEmpty()
                    detail == "Нужна HTTPS-ссылка на подписку." || detail == "Эта подписка уже добавлена." -> detail
                    else -> "Операция не выполнена. Проверьте формат файла или подписки и доступность сети."
                })
            } finally {
                busy = false
                if (!isDestroyed) render()
            }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun panel() = column().apply {
        background = GradientDrawable().apply { setColor(getColor(R.color.ou_panel)); cornerRadius = 18.dp().toFloat() }
        setPadding(15.dp(), 12.dp(), 15.dp(), 12.dp())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp() }
    }
    private fun label(value: String, size: Float = 14f, tint: Int = R.color.ou_text, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(getColor(tint)); setPadding(0, 5.dp(), 0, 5.dp())
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(value: String, enabled: Boolean = true, primary: Boolean = false, click: () -> Unit) = MaterialButton(this).apply {
        text = value; isAllCaps = false; isEnabled = enabled; minHeight = 50.dp(); cornerRadius = 13.dp()
        backgroundTintList = ColorStateList.valueOf(getColor(if (primary && enabled) R.color.ou_mint else R.color.ou_panel))
        setTextColor(getColor(if (primary && enabled) R.color.ou_base else if (enabled) R.color.ou_text else R.color.ou_dim))
        setOnClickListener { click() }
    }
    private fun show(value: String) { message = value; render() }
    private fun format(s: NodeSpeed) = "↓ ${number(s.downloadMbps)} · ↑ ${s.uploadMbps?.let(::number) ?: "—"} Мбит/с · до первого байта ${s.latencyMs} мс"
    private fun number(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
