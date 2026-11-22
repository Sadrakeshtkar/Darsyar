package com.darsyar.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class StudyEntry(
    val id: Long, val subject: String, val topic: String, val minutes: Int,
    val tests: Int, val correct: Int, val wrong: Int, val skipped: Int, val date: Long
)
data class StudyPlan(val id: Int, val subject: String, val topic: String, val time: String, val done: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }
        setContent { DarsYarApp() }
    }
}

private fun todayStart(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
private fun fmtMinutes(m: Int) = "${m / 60} ساعت و ${m % 60} دقیقه"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarsYarApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("darsyar", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    var entries by remember { mutableStateOf(loadEntries(prefs)) }
    var plans by remember { mutableStateOf(loadPlans(prefs)) }
    var tab by remember { mutableIntStateOf(0) }
    var showAddPlan by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf<StudyPlan?>(null) }
    var showExam by remember { mutableStateOf(false) }
    var goalMinutes by remember { mutableIntStateOf(prefs.getInt("goal", 240)) }
    var goalTests by remember { mutableIntStateOf(prefs.getInt("goalTests", 40)) }
    var examCount by remember { mutableIntStateOf(prefs.getInt("exams", 0)) }

    fun persist() {
        saveEntries(prefs, entries); savePlans(prefs, plans)
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8B9CFF), secondary = Color(0xFF55C7A5))
                  else lightColorScheme(primary = Color(0xFF4056B5), secondary = Color(0xFF16856A))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Column { Text("درس‌یار", fontWeight = FontWeight.Bold); Text("همراه برنامه‌ریزی و پیشرفت تحصیلی", fontSize = 11.sp) } },
                        actions = { IconButton(onClick = { dark = !dark; prefs.edit().putBoolean("dark", dark).apply() }) {
                            Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "تغییر پوسته")
                        } }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        listOf("امروز" to Icons.Default.Today, "آمار" to Icons.Default.BarChart,
                            "درس‌ها" to Icons.Default.MenuBook, "ابزارها" to Icons.Default.Tune).forEachIndexed { i, item ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                icon = { Icon(item.second, null) }, label = { Text(item.first) })
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == 0) FloatingActionButton(onClick = { showAddPlan = true }) { Icon(Icons.Default.Add, "افزودن پارت") }
                }
            ) { padding ->
                              when (tab) {
                    0 -> TodayScreen(plans, entries, goalMinutes, goalTests,
                        onAdd = { showAddPlan = true },
                        onDone = { p -> showLog = p },
                        modifier = Modifier.padding(padding))
                    1 -> StatsScreen(entries, modifier = Modifier.padding(padding))
                    2 -> SubjectsScreen(entries, modifier = Modifier.padding(padding))
                    else -> ToolsScreen(
                        goalMinutes, goalTests, examCount,
                        onGoals = { m, t -> goalMinutes = m; goalTests = t; prefs.edit().putInt("goal",m).putInt("goalTests",t).apply() },
                        onExam = { showExam = true },
                        onBackup = { shareBackup(context, entries, plans) },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
            if (showAddPlan) AddPlanDialog(
                onDismiss = { showAddPlan = false },
                onSave = { subject, topic, time ->
                    val p = StudyPlan((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), subject, topic, time, false)
                    plans = plans + p; savePlans(prefs, plans); scheduleReminder(context, p)
                    showAddPlan = false
                })
            showLog?.let { p -> LogStudyDialog(p, onDismiss = { showLog = null }, onSave = { minutes, tests, correct, wrong, skipped ->
                entries = entries + StudyEntry(System.currentTimeMillis(), p.subject, p.topic, minutes, tests, correct, wrong, skipped, System.currentTimeMillis())
                plans = plans.map { if (it.id == p.id) it.copy(done = true) else it }
                persist(); showLog = null
            }) }
            if (showExam) ExamDialog(onDismiss = { showExam = false }, onSave = { score ->
                examCount += 1; prefs.edit().putInt("exams", examCount).apply(); showExam = false
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(plans: List<StudyPlan>, entries: List<StudyEntry>, goalMinutes: Int, goalTests: Int,
                onAdd: () -> Unit, onDone: (StudyPlan) -> Unit, modifier: Modifier = Modifier) {
    val today = entries.filter { it.date >= todayStart() }
    val mins = today.sumOf { it.minutes }; val tests = today.sumOf { it.tests }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("برنامه امروز", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(SimpleDateFormat("EEEE، d MMMM", Locale("fa")).format(Date()), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("مطالعه امروز", fmtMinutes(mins), "هدف ${goalMinutes / 60} ساعت", Modifier.weight(1f))
                MetricCard("تست امروز", tests.toString(), "هدف $goalTests تست", Modifier.weight(1f))
            }
        }
        item {
            val percent = if (goalMinutes > 0) (mins.toFloat() / goalMinutes).coerceIn(0f,1f) else 0f
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("پیشرفت هدف مطالعه", fontWeight = FontWeight.SemiBold)
                        Text("${(percent*100).toInt()}٪")
                    }
                    LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item { Text("پارت‌های برنامه‌ریزی‌شده", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        if (plans.isEmpty()) item { EmptyCard("هنوز پارت مطالعاتی ثبت نکردی. از دکمه + استفاده کن.") }
        items(plans) { p ->
            Card {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.subject, fontWeight = FontWeight.Bold)
                        Text("${p.topic}  •  ${p.time}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    if (p.done) AssistChip(onClick = {}, label = { Text("انجام شد") }, leadingIcon = { Icon(Icons.Default.Check, null) })
                    else Button(onClick = { onDone(p) }) { Text("اتمام پارت") }
                }
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    } }
}
@Composable fun EmptyCard(msg: String) { Card { Text(msg, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
fun StatsScreen(entries: List<StudyEntry>, modifier: Modifier = Modifier) {
    var period by remember { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()
    val start = when(period) { 0 -> todayStart(); 1 -> now - 7L*24*3600*1000; else -> now - 30L*24*3600*1000 }
    val data = entries.filter { it.date >= start }
    val mins = data.sumOf { it.minutes }; val tests = data.sumOf { it.tests }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("گزارش عملکرد", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("روزانه", "هفتگی", "ماهانه").forEachIndexed { i, s ->
                    SegmentedButton(selected = period == i, onClick = { period = i }, shape = SegmentedButtonDefaults.itemShape(i,3)) { Text(s) }
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("ساعت مطالعه", fmtMinutes(mins), "در بازه انتخابی", Modifier.weight(1f))
            MetricCard("تعداد تست", tests.toString(), "در بازه انتخابی", Modifier.weight(1f))
        } }
        item { Text("نمودار ساعت مطالعه", fontWeight = FontWeight.Bold) }
        item { SimpleBarChart(data.map { it.minutes }, "دقیقه") }
        item { Text("نمودار تعداد تست", fontWeight = FontWeight.Bold) }
        item { SimpleBarChart(data.map { it.tests }, "تست") }
        item { Text("تعداد پارت‌های ثبت‌شده: ${data.size}") }
    }
}

@Composable
fun SubjectsScreen(entries: List<StudyEntry>, modifier: Modifier = Modifier) {
    val subjects = entries.groupBy { it.subject }
    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("درس‌ها", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        if (subjects.isEmpty()) {
            item { EmptyCard("هنوز گزارشی ثبت نشده است.") }
        }
        items(subjects.keys.toList()) { subject ->
            val records = subjects[subject].orEmpty()
            val minutes = records.sumOf { it.minutes }
            val tests = records.sumOf { it.tests }
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(subject, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("زمان مطالعه: ${fmtMinutes(minutes)}")
                    Text("تعداد تست: $tests")
                    Text("تعداد جلسات: ${records.size}")
                }
            }
        }
    }
}

@Composable
fun ToolsScreen(
    goalMinutes: Int,
    goalTests: Int,
    examCount: Int,
    onGoals: (Int, Int) -> Unit,
    onExam: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    var studyGoal by remember { mutableStateOf(goalMinutes.toString()) }
    var testGoal by remember { mutableStateOf(goalTests.toString()) }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("ابزارها و تنظیمات", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("هدف روزانه", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = studyGoal,
                        onValueChange = { studyGoal = it },
                        label = { Text("دقیقه مطالعه") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = testGoal,
                        onValueChange = { testGoal = it },
                        label = { Text("تعداد تست") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onGoals(
                                studyGoal.toIntOrNull()?.coerceAtLeast(0) ?: goalMinutes,
                                testGoal.toIntOrNull()?.coerceAtLeast(0) ?: goalTests
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره اهداف")
                    }
                }
            }
        }
        item {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("آزمون‌ها", fontWeight = FontWeight.Bold)
                    Text("آزمون‌های ثبت‌شده: $examCount")
                    Button(onClick = onExam) {
                        Text("ثبت آزمون جدید")
                    }
                }
            }
        }
        item {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("پشتیبان‌گیری", fontWeight = FontWeight.Bold)
                    Text("از اطلاعات برنامه نسخه پشتیبان تهیه کن.")
                    OutlinedButton(
                        onClick = onBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("خروجی گرفتن از اطلاعات")
                    }
                }
            }
        }
    }
}

@Composable
fun AddPlanDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Int) -> Unit
) {
    var subject by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("60") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن پارت مطالعاتی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it }, label = { Text("نام درس") })
                OutlinedTextField(topic, { topic = it }, label = { Text("مبحث") })
                OutlinedTextField(time, { time = it }, label = { Text("مدت به دقیقه") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = time.toIntOrNull() ?: 60
                if (subject.isNotBlank() && topic.isNotBlank() && minutes > 0) {
                    onSave(subject, topic, minutes)
                }
            }) { Text("ثبت") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } }
    )
}

@Composable
fun LogStudyDialog(
    plan: StudyPlan,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int, Int) -> Unit
) {
    var minutes by remember { mutableStateOf(plan.time.toString()) }
    var tests by remember { mutableStateOf("0") }
    var correct by remember { mutableStateOf("0") }
    var wrong by remember { mutableStateOf("0") }
    var skipped by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت عملکرد مطالعه") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${plan.subject} - ${plan.topic}")
                OutlinedTextField(minutes, { minutes = it }, label = { Text("دقیقه مطالعه") })
                OutlinedTextField(tests, { tests = it }, label = { Text("تعداد تست") })
                OutlinedTextField(correct, { correct = it }, label = { Text("درست") })
                OutlinedTextField(wrong, { wrong = it }, label = { Text("غلط") })
                OutlinedTextField(skipped, { skipped = it }, label = { Text("نزده") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    minutes.toIntOrNull() ?: 0,
                    tests.toIntOrNull() ?: 0,
                    correct.toIntOrNull() ?: 0,
                    wrong.toIntOrNull() ?: 0,
                    skipped.toIntOrNull() ?: 0
                )
            }) { Text("ثبت") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } }
    )
}

@Composable
fun ExamDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var score by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت آزمون") },
        text = {
            OutlinedTextField(
                value = score,
                onValueChange = { score = it },
                label = { Text("درصد یا نمره") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(score.toIntOrNull() ?: 0) }) {
                Text("ثبت")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } }
    )
}

@Composable
fun SimpleBarChart(values: List<Int>, label: String) {
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (values.isEmpty()) {
                Text("هنوز داده‌ای برای نمایش وجود ندارد.")
            } else {
                values.takeLast(7).forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", modifier = Modifier.width(24.dp))
                        LinearProgressIndicator(
                            progress = { value.toFloat() / maxValue },
                            modifier = Modifier.weight(1f).height(10.dp)
                        )
                        Text(" $value $label")
                    }
                }
            }
        }
    }
}

fun loadEntries(prefs: android.content.SharedPreferences): List<StudyEntry> {
    return try {
        val json = org.json.JSONArray(prefs.getString("entries", "[]"))
        List(json.length()) { i ->
            val o = json.getJSONObject(i)
            StudyEntry(
                o.getLong("date"),
                o.getString("subject"),
                o.getString("topic"),
                o.getInt("minutes"),
                o.getInt("tests"),
                o.getInt("correct"),
                o.getInt("wrong"),
                o.getInt("skipped"),
                o.getLong("created")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveEntries(
    prefs: android.content.SharedPreferences,
    entries: List<StudyEntry>
) {
    val json = org.json.JSONArray()
    entries.forEach { e ->
        val o = org.json.JSONObject()
        o.put("date", e.date)
        o.put("subject", e.subject)
        o.put("topic", e.topic)
        o.put("minutes", e.minutes)
        o.put("tests", e.tests)
        o.put("correct", e.correct)
        o.put("wrong", e.wrong)
        o.put("skipped", e.skipped)
        o.put("created", e.created)
        json.put(o)
    }
    prefs.edit().putString("entries", json.toString()).apply()
}

fun loadPlans(prefs: android.content.SharedPreferences): List<StudyPlan> {
    return try {
        val json = org.json.JSONArray(prefs.getString("plans", "[]"))
        List(json.length()) { i ->
            val o = json.getJSONObject(i)
            StudyPlan(
                o.getInt("id"),
                o.getString("subject"),
                o.getString("topic"),
                o.getInt("time"),
                o.getBoolean("done")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun savePlans(
    prefs: android.content.SharedPreferences,
    plans: List<StudyPlan>
) {
    val json = org.json.JSONArray()
    plans.forEach { p ->
        val o = org.json.JSONObject()
        o.put("id", p.id)
        o.put("subject", p.subject)
        o.put("topic", p.topic)
        o.put("time", p.time)
        o.put("done", p.done)
        json.put(o)
    }
    prefs.edit().putString("plans", json.toString()).apply()
}

fun shareBackup(
    context: android.content.Context,
    entries: List<StudyEntry>,
    plans: List<StudyPlan>
) {
    val backup = org.json.JSONObject()
    backup.put("entries", entries.size)
    backup.put("plans", plans.size)

    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            android.content.Intent.EXTRA_TEXT,
            "پشتیبان درس‌یار\n$backup"
        )
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, "اشتراک‌گذاری پشتیبان")
    )
}

fun scheduleReminder(
    context: android.content.Context,
    plan: StudyPlan
) {
    // یادآور در نسخه بعدی فعال می‌شود.
}
