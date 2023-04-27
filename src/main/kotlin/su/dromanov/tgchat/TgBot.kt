package su.dromanov.tgchat

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import net.md_5.bungee.api.chat.BaseComponent
import okhttp3.OkHttpClient
import okhttp3.internal.wait
import okhttp3.logging.HttpLoggingInterceptor
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.command.CommandException
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.conversations.Conversable
import org.bukkit.conversations.Conversation
import org.bukkit.conversations.ConversationAbandonedEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionAttachmentInfo
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Duration
import su.dromanov.tgchat.Constants as C

typealias CmdHandler = suspend (HandlerContext) -> Unit

data class HandlerContext(
    val update: Update,
    val message: Message?,
    val chat: Chat?,
    val commandArgs: List<String> = listOf(),
)

class TgCommandSender(
    private val name: String,
    private val onReply: ((Array<out String>) -> Unit)
) : RemoteConsoleCommandSender {
    override fun isOp(): Boolean {
        return true
    }

    override fun setOp(value: Boolean) {}

    override fun isPermissionSet(name: String): Boolean {
        return true
    }

    override fun isPermissionSet(perm: Permission): Boolean {
        return true
    }

    override fun hasPermission(name: String): Boolean {
        return true
    }

    override fun hasPermission(perm: Permission): Boolean {
        return true
    }

    override fun addAttachment(plugin: org.bukkit.plugin.Plugin, name: String, value: Boolean): PermissionAttachment {
        TODO("Not implemented")
    }

    override fun addAttachment(plugin: org.bukkit.plugin.Plugin): PermissionAttachment {
        TODO("Not implemented")
    }

    override fun addAttachment(
        plugin: org.bukkit.plugin.Plugin,
        name: String,
        value: Boolean,
        ticks: Int
    ): PermissionAttachment? {
        return null
    }

    override fun addAttachment(plugin: org.bukkit.plugin.Plugin, ticks: Int): PermissionAttachment? {
        return null
    }

    override fun removeAttachment(attachment: PermissionAttachment) {}

    override fun recalculatePermissions() {}

    override fun getEffectivePermissions(): MutableSet<PermissionAttachmentInfo> {
        TODO("Not implemented")
    }

    override fun sendMessage(message: String) {
        onReply.invoke(arrayOf(message))
    }

    override fun sendMessage(messages: Array<out String>) {
        onReply.invoke(messages)
    }

    override fun getServer(): Server {
        return Bukkit.getServer()
    }

    override fun getName(): String {
        return name
    }

    override fun spigot(): CommandSender.Spigot {
        return object : CommandSender.Spigot() {
            override fun sendMessage(component: BaseComponent) {
                onReply.invoke(arrayOf(component.toPlainText()))
            }

            override fun sendMessage(vararg components: BaseComponent?) {
                val builder = StringBuilder()
                for (component in components) {
                    component?.run { builder.append(toPlainText()) }
                }
                onReply.invoke(arrayOf(builder.toString()))
            }
        }
    }
}

class TgBot(
    private val plugin: Plugin,
    private val config: Configuration,
) {
    private val client: OkHttpClient = OkHttpClient
        .Builder()
        // Disable timeout to make long-polling possible
        .readTimeout(Duration.ZERO)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level =
                    if (config.debugHttp) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
            }
        )
        .build()
    private val api = Retrofit.Builder()
        .baseUrl("${config.apiOrigin}/bot${config.botToken}/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TgApiService::class.java)
    private val updateChan = Channel<Update>()
    private var pollJob: Job? = null
    private var handlerJob: Job? = null
    private var currentOffset: Long = -1
    private var me: User? = null
    private var commandRegex: Regex? = null
    private val commandMap: Map<String?, CmdHandler> = config.commands.run {
        mapOf(
            online to ::onlineHandler,
            time to ::timeHandler,
            chatID to ::chatIdHandler,
            cmd to ::cmdHandler
            // TODO:
            // linkIgn to ::linkIgnHandler,
            // getAllLinked to ::getLinkedUsersHandler,
        )
    }

    private suspend fun initialize() {
        me = api.getMe().result!!
        commandRegex = """^/(\w+)(@${me!!.username})?(?:\s+(.+))?$""".toRegex()
        val commands = config.commands.run { listOf(time, online, chatID, cmd) }
            .zip(
                C.COMMAND_DESC.run {
                    listOf(timeDesc, onlineDesc, chatIDDesc, cmdDesc)
                }
            )
            .map { BotCommand(it.first!!, it.second) }
            .let { SetMyCommands(it) }
        api.deleteWebhook(dropPendingUpdates = true)
        api.setMyCommands(commands)
    }

    suspend fun startPolling() {
        initialize()
        pollJob = initPolling()
        handlerJob = initHandler()
    }

    suspend fun stop() {
        pollJob?.cancelAndJoin()
        handlerJob?.join()
    }

    private fun initPolling() = plugin.launch {
        loop@ while (true) {
            try {
                api.getUpdates(
                    offset = currentOffset,
                    timeout = config.pollTimeout,
                ).result?.let { updates ->
                    if (updates.isNotEmpty()) {
                        updates.forEach { updateChan.send(it) }
                        currentOffset = updates.last().updateId + 1
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> break@loop
                    else -> {
                        e.printStackTrace()
                        continue@loop
                    }
                }
            }
        }
        updateChan.close()
    }

    private fun initHandler() = plugin.launch {
        updateChan.consumeEach {
            try {
                handleUpdate(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleUpdate(update: Update) {
        // Ignore private message or channel post
        if (listOf("private", "channel").contains(update.message?.chat?.type))
            return
        val ctx = HandlerContext(
            update,
            update.message,
            update.message?.chat,
        )
        update.message?.let {
            it.text?.let {
                commandRegex?.matchEntire(it)?.groupValues?.let { matchList ->
                    commandMap[matchList[1]]?.run {
                        val args = matchList[2].split("\\s+".toRegex())
                        this(ctx.copy(commandArgs = args))
                    }
                }
            } ?: run {
                onMessageHandler(ctx)
            }
        }
    }

    private suspend fun timeHandler(ctx: HandlerContext) {
        val msg = ctx.message!!
        if (!config.allowedChats.contains(msg.chat.id)) {
            return
        }
        if (plugin.server.worlds.isEmpty()) {
            api.sendMessage(
                msg.chat.id,
                "No worlds available",
                replyToMessageId = msg.messageId
            )
            return
        }
        // TODO: handle multiple worlds
        val time = plugin.server.worlds.first().time
        val text = C.TIMES_OF_DAY.run {
            when {
                time <= 12000 -> day
                time <= 13800 -> sunset
                time <= 22200 -> night
                time <= 24000 -> sunrise
                else -> ""
            }
        } + " ($time)"
        api.sendMessage(msg.chat.id, text, replyToMessageId = msg.messageId)
    }

    private suspend fun onlineHandler(ctx: HandlerContext) {
        val msg = ctx.message!!
        if (!config.allowedChats.contains(msg.chat.id)) {
            return
        }
        val playerList = plugin.server.onlinePlayers
        val playerStr = plugin.server
            .onlinePlayers
            .mapIndexed { i, s -> "${i + 1}. ${s.displayName.fullEscape()}" }
            .joinToString("\n")
        val text =
            if (playerList.isNotEmpty()) "${config.onlineString}:\n$playerStr"
            else config.nobodyOnlineString
        api.sendMessage(msg.chat.id, text, replyToMessageId = msg.messageId)
    }

    private suspend fun chatIdHandler(ctx: HandlerContext) {
        val msg = ctx.message!!
        val chatId = msg.chat.id
        val text = """
        |Chat ID: <code>$chatId</code>.
        |Copy this id to <code>chats</code> section in your <b>config.yml</b> file so it looks like this:
        |<pre>
        |chats: [
        |  $chatId,
        |  # other chat ids...
        |]
        |</pre>
        |Your user id: <code>${msg.from?.id}</code>
        """.trimMargin()
        api.sendMessage(chatId, text, replyToMessageId = msg.messageId)
    }

    private suspend fun cmdHandler(ctx: HandlerContext) {
        val msg = ctx.message!!
        val chatId = msg.chat.id
        if (!config.allowedChats.contains(msg.chat.id)) {
            return
        }
        if (msg.text == null) {
            return
        }
        if (msg.from?.id !in config.chatAdmins) {
            api.sendMessage(chatId, "Недоступно.", replyToMessageId = msg.messageId)
            return
        }
        val cmd = msg.text.split(" ", limit = 2).getOrNull(1)
        if (cmd == null) {
            api.sendMessage(chatId, "Требуется команда для выполнения.", replyToMessageId = msg.messageId)
            return
        }

        var sentText = config.commandRunningString.replace("%command%", cmd)
        var sent = api.sendMessage(
            chatId,
            sentText,
            replyToMessageId = msg.messageId
        ).result!!

        TgCommandSender("(tg) ${msg.from!!.rawUserMention()}") { s ->
            val text = config.commandResultLine
                .replace("%result%", s.joinToString("\n").escapeColorCodes().escapeHtml())

            sentText += text
            runBlocking {
                sent = api.editMessage(chatId, sent.messageId, sentText).result!!
            }
        }.let {
            Bukkit.getScheduler().callSyncMethod(plugin) {
                try {
                    plugin.server.dispatchCommand(it, cmd)
                } catch (e: CommandException) {
                    plugin.server.run {
                        dispatchCommand(consoleSender, cmd)
                    }
//                    sentText += config.commandNoOutput
                    sentText += "\n<pre>${e.stackTraceToString()}</pre>"
                    runBlocking {
                        sent = api.editMessage(chatId, sent.messageId, sentText).result!!
                    }
                }
            }
        }
    }

    private suspend fun linkIgnHandler(ctx: HandlerContext) {
        val tgUser = ctx.message!!.from!!
        val mcUuid = getMinecraftUuidByUsername(ctx.message.text!!)
        if (mcUuid == null || ctx.commandArgs.isEmpty()) {
            // Respond...
            return
        }
        val (minecraftIgn) = ctx.commandArgs
        val linked = plugin.ignAuth?.linkUser(
            tgId = tgUser.id,
            tgFirstName = tgUser.firstName,
            tgLastName = tgUser.lastName,
            minecraftUsername = minecraftIgn,
            minecraftUuid = mcUuid,
        ) ?: false
        if (linked) {
            // TODO
        }
    }

    private suspend fun getLinkedUsersHandler(ctx: HandlerContext) {
        val linkedUsers = plugin.ignAuth?.run {
            getAllLinkedUsers()
        } ?: listOf()
        if (linkedUsers.isEmpty()) {
            api.sendMessage(ctx.message!!.chat.id, "No linked users.")
        } else {
            val text = "<b>Linked users:</b>\n" +
                    linkedUsers.mapIndexed { i, dbUser ->
                        "${i + 1}. ${dbUser.fullName()}"
                    }.joinToString("\n")
            api.sendMessage(ctx.message!!.chat.id, text)
        }
    }

    private fun onMessageHandler(ctx: HandlerContext) {
        val msg = ctx.message!!
        if (!config.logFromTGtoMC || msg.from == null)
            return
        plugin.sendMessageToMinecraft(
            text = msg.text ?: msg.caption ?: "",
            replyToMessage = msg.replyToMessage,
            type = plugin.getTypeString(msg),
            username = msg.from.rawUserMention(),
            chatTitle = msg.chat.title,
        )
    }

    suspend fun sendMessageToTelegram(text: String, username: String? = null) {
        val formatted = username?.let {
            config.telegramFormat
                .replace(C.USERNAME_PLACEHOLDER, username.fullEscape())
                .replace(C.MESSAGE_TEXT_PLACEHOLDER, text.escapeHtml())
        } ?: text
        config.allowedChats.forEach { chatId ->
            try {
                api.sendMessage(chatId, formatted, disableNotification = config.silentMessages)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
