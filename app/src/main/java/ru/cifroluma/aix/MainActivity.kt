package ru.cifroluma.aix

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color

val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsKeys {
    val API_KEY = stringPreferencesKey("api_key")
    val MODEL = stringPreferencesKey("model")
    val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
}

data class AppSettings(
    val apiKey: String = "",
    val model: String = "openai/gpt-4o-mini",
    val systemPrompt: String = "Ты полезный ИИ-ассистент. Отвечай кратко и понятно."
)

suspend fun loadSettings(context: Context): AppSettings {
    val prefs = context.settingsDataStore.data.first()

    return AppSettings(
        apiKey = prefs[SettingsKeys.API_KEY] ?: "",
        model = prefs[SettingsKeys.MODEL] ?: "openai/gpt-4o-mini",
        systemPrompt = prefs[SettingsKeys.SYSTEM_PROMPT]
            ?: "Ты полезный ИИ-ассистент. Отвечай кратко и понятно."
    )
}

suspend fun saveSettings(
    context: Context,
    apiKey: String,
    model: String,
    systemPrompt: String
) {
    context.settingsDataStore.edit { prefs ->
        prefs[SettingsKeys.API_KEY] = apiKey
        prefs[SettingsKeys.MODEL] = model
        prefs[SettingsKeys.SYSTEM_PROMPT] = systemPrompt
    }
}

data class UiMessage(
    val role: String,
    val text: String
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chatId"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val chatId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC, id DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun renameChat(
        chatId: Long,
        title: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun touchChat(
        chatId: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearChat(chatId: Long)
}

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aix_chat.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}

private val httpClient = OkHttpClient()
private val AixDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF07111F),

    secondary = Color(0xFFB7C7E6),
    onSecondary = Color(0xFF111827),

    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE5E7EB),

    surface = Color(0xFF111827),
    onSurface = Color(0xFFE5E7EB),

    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFD1D5DB),

    error = Color(0xFFFF8A8A),
    onError = Color(0xFF2A0000)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = AixDarkColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AIXRoot()
                }
            }
        }
    }
}

@Composable
fun AIXRoot() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val chatDao = remember { database.chatDao() }
    val messageDao = remember { database.messageDao() }

    val chats by chatDao.observeChats().collectAsState(initial = emptyList())

    var currentChatId by remember { mutableStateOf<Long?>(null) }

    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("openai/gpt-4o-mini") }
    var systemPrompt by remember {
        mutableStateOf("Ты полезный ИИ-ассистент. Отвечай кратко и понятно.")
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val settings = loadSettings(context)
        apiKey = settings.apiKey
        model = settings.model
        systemPrompt = settings.systemPrompt
    }

    val openedChatId = currentChatId

    if (openedChatId == null) {
        ChatListScreen(
            chats = chats,
            onCreateChat = {
                scope.launch {
                    val now = System.currentTimeMillis()
                    val chatId = chatDao.insert(
                        ChatEntity(
                            title = "Новый чат",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    currentChatId = chatId
                }
            },
            onOpenChat = { chat ->
                currentChatId = chat.id
            },
            onDeleteChat = { chat ->
                scope.launch {
                    chatDao.deleteChat(chat.id)
                }
            }
        )
    } else {
        val currentChat = chats.firstOrNull { it.id == openedChatId }

        ChatScreen(
            chatId = openedChatId,
            chat = currentChat,
            messageDao = messageDao,
            chatDao = chatDao,
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            model = model,
            onModelChange = { model = it },
            systemPrompt = systemPrompt,
            onSystemPromptChange = { systemPrompt = it },
            onBack = {
                currentChatId = null
            },
            onDeleteCurrentChat = {
                scope.launch {
                    chatDao.deleteChat(openedChatId)
                    currentChatId = null
                }
            }
        )
    }
}

@Composable
fun ChatListScreen(
    chats: List<ChatEntity>,
    onCreateChat: () -> Unit,
    onOpenChat: (ChatEntity) -> Unit,
    onDeleteChat: (ChatEntity) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "AIX Chat",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onCreateChat,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Новый чат")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (chats.isEmpty()) {
            Text("Чатов пока нет. Нажми «Новый чат».")
        } else {
            chats.forEach { chat ->
                ChatListItem(
                    chat = chat,
                    onOpen = { onOpenChat(chat) },
                    onDelete = { onDeleteChat(chat) }
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun ChatListItem(
    chat: ChatEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Открыть")
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Удалить")
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    chatId: Long,
    chat: ChatEntity?,
    messageDao: MessageDao,
    chatDao: ChatDao,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    onBack: () -> Unit,
    onDeleteCurrentChat: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val storedMessages by remember(chatId) {
        messageDao.observeMessages(chatId)
    }.collectAsState(initial = emptyList())

    val messages = storedMessages.map {
        UiMessage(
            role = it.role,
            text = it.content
        )
    }

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }

    var titleInput by remember(chat?.id, chat?.title) {
        mutableStateOf(chat?.title ?: "Новый чат")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack
            ) {
                Text("Назад")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = chat?.title ?: "Чат",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showSettings = !showSettings },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (showSettings) "Скрыть настройки" else "Настройки")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        messageDao.clearChat(chatId)
                        chatDao.touchChat(chatId)
                        statusText = "Сообщения очищены."
                        errorText = null
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Очистить")
            }

            OutlinedButton(
                onClick = onDeleteCurrentChat,
                modifier = Modifier.weight(1f)
            ) {
                Text("Удалить")
            }
        }

        if (showSettings) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                label = { Text("Название чата") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val cleanTitle = titleInput.trim().ifBlank { "Новый чат" }

                    scope.launch {
                        chatDao.renameChat(chatId, cleanTitle)
                        statusText = "Название сохранено."
                        errorText = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить название")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("OpenRouter API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text("System prompt") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        saveSettings(
                            context = context,
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            systemPrompt = systemPrompt
                        )

                        statusText = "Настройки сохранены."
                        errorText = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить настройки")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        statusText?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        errorText?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (messages.isEmpty() && streamingText.isBlank()) {
            Text("Пока пусто. Напиши первое сообщение.")
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            messages.forEach { message ->
                MessageCard(message)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (streamingText.isNotBlank()) {
                MessageCard(
                    UiMessage(
                        role = "assistant",
                        text = streamingText
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Сообщение") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val cleanApiKey = apiKey.trim()
                val cleanModel = model.trim()
                val cleanInput = input.trim()

                if (cleanApiKey.isBlank()) {
                    errorText = "Вставь OpenRouter API key."
                    statusText = null
                    return@Button
                }

                if (cleanModel.isBlank()) {
                    errorText = "Укажи model, например openai/gpt-4o-mini."
                    statusText = null
                    return@Button
                }

                if (cleanInput.isBlank()) {
                    errorText = "Напиши сообщение."
                    statusText = null
                    return@Button
                }

                val messagesForApi = messages + UiMessage("user", cleanInput)

                input = ""
                errorText = null
                statusText = null
                isLoading = true

                scope.launch {
                    try {
                        saveSettings(
                            context = context,
                            apiKey = cleanApiKey,
                            model = cleanModel,
                            systemPrompt = systemPrompt
                        )

                        val userMessageTime = System.currentTimeMillis()

                        messageDao.insert(
                            MessageEntity(
                                chatId = chatId,
                                role = "user",
                                content = cleanInput,
                                createdAt = userMessageTime
                            )
                        )

                        val currentTitle = chat?.title ?: "Новый чат"

                        if (currentTitle == "Новый чат") {
                            chatDao.renameChat(
                                chatId = chatId,
                                title = makeTitleFromMessage(cleanInput),
                                updatedAt = userMessageTime
                            )
                        } else {
                            chatDao.touchChat(
                                chatId = chatId,
                                updatedAt = userMessageTime
                            )
                        }

                        streamingText = ""

                        val answer = streamOpenRouterMessage(
                            apiKey = cleanApiKey,
                            model = cleanModel,
                            systemPrompt = systemPrompt,
                            chatMessages = messagesForApi,
                            onDelta = { delta ->
                                streamingText += delta
                            }
                        )

                        val assistantMessageTime = System.currentTimeMillis()

                        messageDao.insert(
                            MessageEntity(
                                chatId = chatId,
                                role = "assistant",
                                content = answer,
                                createdAt = assistantMessageTime
                            )
                        )

                        streamingText = ""

                        chatDao.touchChat(
                            chatId = chatId,
                            updatedAt = assistantMessageTime
                        )
                    } catch (e: Exception) {
                        errorText = e.message ?: "Неизвестная ошибка"
                        streamingText = ""
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Думает..." else "Отправить")
        }
    }
}

@Composable
fun MessageCard(message: UiMessage) {
    val context = LocalContext.current
    val isUser = message.role == "user"

    val bubbleColor = when {
        isUser -> Color(0xFF1D4ED8)
        message.role == "assistant" -> Color(0xFF1F2937)
        else -> Color(0xFF374151)
    }

    val textColor = when {
        isUser -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(
                fraction = if (isUser) 0.88f else 0.96f
            ),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = bubbleColor,
                contentColor = textColor
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (message.role) {
                            "user" -> "Ты"
                            "assistant" -> "AIX"
                            else -> message.role
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = {
                            copyToClipboard(
                                context = context,
                                label = "AIX message",
                                text = message.text
                            )
                        }
                    ) {
                        Text(
                            text = "Copy",
                            color = if (isUser) Color.White else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                CompositionLocalProvider(
                    LocalContentColor provides textColor
                ) {
                    MarkdownText(text = message.text)
                }
            }
        }
    }
}

sealed class MarkdownBlock

data class MdHeading(
    val level: Int,
    val text: String
) : MarkdownBlock()

data class MdParagraph(
    val text: String
) : MarkdownBlock()

data class MdBullet(
    val text: String
) : MarkdownBlock()

data class MdNumbered(
    val number: String,
    val text: String
) : MarkdownBlock()

data class MdCodeBlock(
    val code: String
) : MarkdownBlock()

object MdSpacer : MarkdownBlock()

private val numberedListRegex = Regex("""^(\d+)\.\s+(.*)$""")

@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) {
        parseMarkdown(text)
    }

    Column {
        blocks.forEach { block ->
            when (block) {
                is MdHeading -> {
                    Text(
                        text = markdownInline(block.text),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                }

                is MdParagraph -> {
                    Text(
                        text = markdownInline(block.text),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                }

                is MdBullet -> {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = markdownInline(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }

                is MdNumbered -> {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${block.number}. ",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = markdownInline(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }

                is MdCodeBlock -> {
                    CodeBlockView(code = block.code)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                MdSpacer -> {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlockView(code: String) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF020617),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Код",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = {
                        copyToClipboard(
                            context = context,
                            label = "AIX code",
                            text = code
                        )
                    }
                ) {
                    Text("Копировать код")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = code.ifBlank { " " },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<MarkdownBlock>()

    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                val codeLines = mutableListOf<String>()
                i++

                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }

                if (i < lines.size) {
                    i++
                }

                blocks.add(MdCodeBlock(codeLines.joinToString("\n")))
            }

            trimmed.isBlank() -> {
                blocks.add(MdSpacer)
                i++
            }

            trimmed.startsWith("### ") -> {
                blocks.add(MdHeading(level = 3, text = trimmed.removePrefix("### ").trim()))
                i++
            }

            trimmed.startsWith("## ") -> {
                blocks.add(MdHeading(level = 2, text = trimmed.removePrefix("## ").trim()))
                i++
            }

            trimmed.startsWith("# ") -> {
                blocks.add(MdHeading(level = 1, text = trimmed.removePrefix("# ").trim()))
                i++
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                blocks.add(MdBullet(text = trimmed.drop(2).trim()))
                i++
            }

            numberedListRegex.matches(trimmed) -> {
                val match = numberedListRegex.find(trimmed)

                if (match != null) {
                    val number = match.groupValues[1]
                    val itemText = match.groupValues[2]

                    blocks.add(
                        MdNumbered(
                            number = number,
                            text = itemText
                        )
                    )
                }

                i++
            }

            else -> {
                val paragraphLines = mutableListOf<String>()

                while (i < lines.size) {
                    val current = lines[i]
                    val currentTrimmed = current.trim()

                    val isBoundary =
                        currentTrimmed.isBlank() ||
                                currentTrimmed.startsWith("```") ||
                                currentTrimmed.startsWith("# ") ||
                                currentTrimmed.startsWith("## ") ||
                                currentTrimmed.startsWith("### ") ||
                                currentTrimmed.startsWith("- ") ||
                                currentTrimmed.startsWith("* ") ||
                                numberedListRegex.matches(currentTrimmed)

                    if (isBoundary) {
                        break
                    }

                    paragraphLines.add(current)
                    i++
                }

                blocks.add(
                    MdParagraph(
                        text = paragraphLines.joinToString("\n").trim()
                    )
                )
            }
        }
    }

    return blocks
}

fun markdownInline(text: String) = buildAnnotatedString {
    var index = 0

    while (index < text.length) {
        val start = text.indexOf("**", startIndex = index)

        if (start == -1) {
            append(text.substring(index))
            break
        }

        append(text.substring(index, start))

        val end = text.indexOf("**", startIndex = start + 2)

        if (end == -1) {
            append(text.substring(start))
            break
        }

        withStyle(
            style = SpanStyle(
                fontWeight = FontWeight.Bold
            )
        ) {
            append(text.substring(start + 2, end))
        }

        index = end + 2
    }
}

fun copyToClipboard(
    context: Context,
    label: String,
    text: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)

    clipboard.setPrimaryClip(clip)

    Toast.makeText(
        context,
        "Скопировано",
        Toast.LENGTH_SHORT
    ).show()
}

fun makeTitleFromMessage(text: String): String {
    val clean = text
        .replace("\n", " ")
        .trim()

    if (clean.isBlank()) {
        return "Новый чат"
    }

    return if (clean.length <= 35) {
        clean
    } else {
        clean.take(35) + "..."
    }
}

suspend fun streamOpenRouterMessage(
    apiKey: String,
    model: String,
    systemPrompt: String,
    chatMessages: List<UiMessage>,
    onDelta: suspend (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val messagesJson = JSONArray()

    if (systemPrompt.isNotBlank()) {
        messagesJson.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt)
        )
    }

    chatMessages.forEach { message ->
        messagesJson.put(
            JSONObject()
                .put("role", message.role)
                .put("content", message.text)
        )
    }

    val bodyJson = JSONObject()
        .put("model", model)
        .put("messages", messagesJson)
        .put("stream", true)
        .toString()

    val request = Request.Builder()
        .url("https://openrouter.ai/api/v1/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("Content-Type", "application/json")
        .addHeader("X-OpenRouter-Title", "AIX Android")
        .post(bodyJson.toRequestBody("application/json".toMediaType()))
        .build()

    httpClient.newCall(request).execute().use { response ->
        val responseBody = response.body
            ?: throw Exception("Empty response body")

        if (!response.isSuccessful) {
            val errorText = responseBody.string()
            throw Exception("OpenRouter error ${response.code}: $errorText")
        }

        val fullAnswer = StringBuilder()
        val reader = responseBody.charStream().buffered()

        while (true) {
            val line = reader.readLine() ?: break

            if (line.isBlank()) {
                continue
            }

            if (!line.startsWith("data:")) {
                continue
            }

            val data = line.removePrefix("data:").trim()

            if (data == "[DONE]") {
                break
            }

            try {
                val json = JSONObject(data)
                val choices = json.optJSONArray("choices") ?: continue

                if (choices.length() == 0) {
                    continue
                }

                val choice = choices.getJSONObject(0)
                val deltaObject = choice.optJSONObject("delta")
                val delta = deltaObject?.optString("content").orEmpty()

                if (delta.isNotEmpty()) {
                    fullAnswer.append(delta)

                    withContext(Dispatchers.Main) {
                        onDelta(delta)
                    }
                }
            } catch (_: Exception) {
                // Иногда в stream могут прилетать служебные куски.
                // Для MVP просто пропускаем плохо распарсенные строки.
            }
        }

        val result = fullAnswer.toString()

        if (result.isBlank()) {
            "Пустой ответ модели."
        } else {
            result
        }
    }
}