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
fun SimpleBarChart(values: List<Int>, label: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            if (values.isEmpty()) Text("برای نمایش نمودار، ابتدا گزارش مطالعه ثبت کن.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                val buckets = values.takeLast(7)
                val max = (buckets.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
                Canvas(Modifier.fillMaxWidth().height(145.dp)) {
                    val gap = 12.dp.toPx()
                    val barW = (size.width - gap * (buckets.size + 1)) / buckets.size
                    buckets.forEachIndexed { i, v ->
                        val h = (v / max) * (size.height - 10.dp.toPx())
                        val x = gap + i * (barW + gap)
                        drawRoundRect(
                            color = if (label == "تست") Color(0xFF55C7A5) else Color(0xFF8292F4),
                            topLeft = Offset(x, size.height - h), size = androidx.compose.ui.geometry.Size(barW, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                        )
                    }
                }
                Text("نمایش آخرین ۷ پارت ثبت‌شده • واحد: $label", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SubjectsScreen(entries: List<StudyEntry>, modifier: Modifier = Modifier) {
    val subjects = entries.groupBy { it.subject }.toList().sortedByDescending { it.second.sumOf { e -> e.minutes } }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("عملکرد درس‌ها", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        if (subjects.isEmpty()) item { EmptyCard("بعد از ثبت مطالعه، آمار هر درس اینجا نمایش داده می‌شود.") }
        items(subjects) { (name, list) ->
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("مطالعه: ${fmtMinutes(list.sumOf { it.minutes })}")
                Text("تعداد تست: ${list.sumOf { it.tests }}")
                Text("صحیح: ${list.sumOf { it.correct }}  •  غلط: ${list.sumOf { it.wrong }}  •  نزده: ${list.sumOf { it.skipped }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } }
        }
    }
}

@Composable
fun ToolsScreen(goalMinutes: Int, goalTests: Int, exams: Int,
                onGoals: (Int,Int)->Unit, onExam: ()->Unit, onBackup: ()->Unit, modifier: Modifier = Modifier) {
    var m by remember(goalMinutes) { mutableStateOf(goalMinutes.toString()) }
    var t by remember(goalTests) { mutableStateOf(goalTests.toString()) }
    var timerRunning by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(timerRunning) {
        while (timerRunning) { kotlinx.coroutines.delay(1000); seconds++ }
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("ابزارها و تنظیمات", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("تایمر مطالعه", fontWeight = FontWeight.Bold)
                Text("%02d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { timerRunning = !timerRunning }) { Text(if (timerRunning) "توقف" else "شروع") }
                    OutlinedButton(onClick = { timerRunning = false; seconds = 0 }) { Text("بازنشانی") }
                }
                Text("تایمر فعلاً مستقل است؛ برای ثبت گزارش، نتیجه را در فرم اتمام پارت وارد کن.", fontSize = 11.sp)
            } }
        }
        item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اهداف روزانه", fontWeight = FontWeight.Bold)
                OutlinedTextField(m, { m = it.filter(Char::isDigit) }, label = { Text("هدف مطالعه (دقیقه)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(t, { t = it.filter(Char::isDigit) }, label = { Text("هدف تست") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(onClick = { onGoals(m.toIntOrNull() ?: 240, t.toIntOrNull() ?: 40) }) { Text("ذخیره اهداف") }
            } }
        }
        item { Card { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("آزمون‌ها", fontWeight = FontWeight.Bold); Text("تعداد آزمون‌های ثبت‌شده: $exams") }
            Button(onClick = onExam) { Text("ثبت آزمون") }
        } } }
        item { OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("خروجی پشتیبان از اطلاعات")
        } }
        item { Text("اطلاعات در حال حاضر روی همین دستگاه ذخیره می‌شود. فایل پشتیبان را در محل امن نگه دار.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun AddPlanDialog(onDismiss: ()->Unit, onSave: (String,String,String)->Unit) {
    var subject by remember { mutableStateOf("") }; var topic by remember { mutableStateOf("") }; var time by remember { mutableStateOf("08:00") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("پارت جدید") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(subject, { subject = it }, label = { Text("نام درس") })
            OutlinedTextField(topic, { topic = it }, label = { Text("مبحث") })
            OutlinedTextField(time, { time = it }, label = { Text("ساعت یادآوری (مثلاً 14:30)") })
        } },
        confirmButton = { Button(enabled = subject.isNotBlank(), onClick = { onSave(subject,topic,time) }) { Text("ذخیره و یادآوری") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
}

@Composable
fun LogStudyDialog(plan: StudyPlan, onDismiss: ()->Unit, onSave: (Int,Int,Int,Int,Int)->Unit) {
    var minutes by remember { mutableStateOf("60") }; var tests by remember { mutableStateOf("0") }
    var correct by remember { mutableStateOf("0") }; var wrong by remember { mutableStateOf("0") }; var skipped by remember { mutableStateOf("0") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("گزارش پارت ${plan.subject}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan.topic)
            @Composable fun field(value: String, set: (String)->Unit, label: String) {
                OutlinedTextField(value, { set(it.filter(Char::isDigit)) }, label = { Text(label) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            field(minutes,{minutes=it},"مدت مطالعه (دقیقه)")
            field(tests,{tests=it},"تعداد کل تست")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.weight(1f)) { field(correct,{correct=it},"صحیح") }
                Box(Modifier.weight(1f)) { field(wrong,{wrong=it},"غلط") }
                Box(Modifier.weight(1f)) { field(skipped,{skipped=it},"نزده") }
            }
        } },
        confirmButton = { Button(onClick = { onSave(minutes.toIntOrNull()?:0, tests.toIntOrNull()?:0, correct.toIntOrNull()?:0, wrong.toIntOrNull()?:0, skipped.toIntOrNull()?:0) }) { Text("ثبت گزارش و اتمام") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
}

@Composable
fun ExamDialog(onDismiss: ()->Unit, onSave: (String)->Unit) {
    var name by remember { mutableStateOf("") }; var score by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("ثبت آزمون") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("نام آزمون")})
            OutlinedTextField(score,{score=it},label={Text("درصد یا نمره")})
        } },
        confirmButton = { Button(onClick = { onSave(score) }) { Text("ثبت") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
}

private fun scheduleReminder(context: Context, plan: StudyPlan) {
    try {
        val parts = plan.time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return
        if (hour !in 0..23 || minute !in 0..59) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY,hour); set(Calendar.MINUTE,minute); set(Calendar.SECOND,0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR,1)
        }
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("subject", "${plan.subject} - ${plan.topic}").putExtra("id",plan.id)
        val pi = PendingIntent.getBroadcast(context, plan.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,cal.timeInMillis,pi)
        else alarm.set(AlarmManager.RTC_WAKEUP,cal.timeInMillis,pi)
    } catch (_: Exception) { }
}

private fun loadEntries(p: android.content.SharedPreferences): List<StudyEntry> = try {
    val a = JSONArray(p.getString("entries","[]"))
    (0 until a.length()).map { i -> a.getJSONObject(i).let {
        StudyEntry(it.getLong("id"),it.getString("subject"),it.optString("topic"),it.getInt("minutes"),it.getInt("tests"),
            it.getInt("correct"),it.getInt("wrong"),it.getInt("skipped"),it.getLong("date"))
    } }
} catch (_: Exception) { emptyList() }
private fun saveEntries(p: android.content.SharedPreferences, list: List<StudyEntry>) {
    val a = JSONArray()
    list.forEach { e -> a.put(JSONObject().put("id",e.id).put("subject",e.subject).put("topic",e.topic).put("minutes",e.minutes)
        .put("tests",e.tests).put("correct",e.correct).put("wrong",e.wrong).put("skipped",e.skipped).put("date",e.date)) }
    p.edit().putString("entries",a.toString()).apply()
}
private fun loadPlans(p: android.content.SharedPreferences): List<StudyPlan> = try {
    val a = JSONArray(p.getString("plans","[]"))
    (0 until a.length()).map { i -> a.getJSONObject(i).let { StudyPlan(it.getInt("id"),it.getString("subject"),it.optString("topic"),it.optString("time"),it.optBoolean("done")) } }
} catch (_: Exception) { emptyList() }
private fun savePlans(p: android.content.SharedPreferences, list: List<StudyPlan>) {
    val a = JSONArray()
    list.forEach { e -> a.put(JSONObject().put("id",e.id).put("subject",e.subject).put("topic",e.topic).put("time",e.time).put("done",e.done)) }
    p.edit().putString("plans",a.toString()).apply()
}
private fun shareBackup(context: Context, entries: List<StudyEntry>, plans: List<StudyPlan>) {
    val data = JSONObject()
    val e = JSONArray(); entries.forEach { x -> e.put(JSONObject().put("subject",x.subject).put("topic",x.topic).put("minutes",x.minutes).put("tests",x.tests).put("correct",x.correct).put("wrong",x.wrong).put("skipped",x.skipped).put("date",x.date)) }
    val p = JSONArray(); plans.forEach { x -> p.put(JSONObject().put("subject",x.subject).put("topic",x.topic).put("time",x.time).put("done",x.done)) }
    data.put("entries",e).put("plans",p)
    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT,data.toString())
    context.startActivity(Intent.createChooser(send,"ذخیره یا ارسال نسخه پشتیبان"))
}
