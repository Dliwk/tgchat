package su.dromanov.tgchat

import org.bukkit.event.HandlerList
import java.lang.Exception
import su.dromanov.tgchat.Constants as C

class Plugin : AsyncJavaPlugin() {
    private var tgBot: TgBot? = null
    private var eventHandler: EventHandler? = null
    private var config: Configuration? = null
    var ignAuth: IgnAuth? = null

    override suspend fun onEnableAsync() {
        try {
            launch {
                config = Configuration(this).also {
                    initializeWithConfig(it)
                }
            }
        } catch (e: Exception) {
            // Configuration file is missing or incomplete
            logger.warning(e.message)
        }
    }

    private suspend fun initializeWithConfig(config: Configuration) {
        if (!config.isEnabled) return

        if (config.enableIgnAuth) {
            val dbFilePath = dataFolder.resolve("spigot-tg-bridge.sqlite")
            ignAuth = IgnAuth(
                fileName = dbFilePath.absolutePath,
                plugin = this,
            )
        }

        tgBot?.run { stop() }
        tgBot = TgBot(this, config).also { bot ->
            bot.startPolling()
            eventHandler = EventHandler(this, config, bot).also {
                server.pluginManager.registerEvents(it, this)
            }
        }

        getCommand(C.COMMANDS.PLUGIN_RELOAD)?.run {
            setExecutor(CommandHandler(this@Plugin))
        }
        config.serverStartMessage?.let {
            tgBot?.sendMessageToTelegram(it)
        }
    }

    override suspend fun onDisableAsync() {
        config?.let fn@{ config ->
            if (!config.isEnabled)
                return@fn
            config.serverStopMessage?.let {
                tgBot?.sendMessageToTelegram(it)
            }
            eventHandler?.let { HandlerList.unregisterAll(it) }
            tgBot?.run { stop() }
            tgBot = null
            ignAuth?.close()
        }
    }

    fun getTypeString(msg: Message): String? = config?.run {
        msg.run {
            photo?.let { photoString }
                ?: audio?.let {
                    audioString.replace(
                        C.MESSAGE_TYPE_FILENAME_PLACEHOLDER,
                        it.fileName ?: "<n/a>"
                    )
                }
                ?: document?.let {
                    documentString.replace(
                        C.MESSAGE_TYPE_FILENAME_PLACEHOLDER,
                        it.fileName ?: "<n/a>"
                    )
                }
                ?: sticker?.let {
                    stickerString.replace(
                        C.MESSAGE_TYPE_EMOJI_PLACEHOLDER,
                        it.emoji.escapeEmoji()
                    )
                }
                ?: video?.let {
                    videoString.replace(
                        C.MESSAGE_TYPE_FILENAME_PLACEHOLDER,
                        it.fileName ?: "<n/a>"
                    )
                }
                ?: videoNote?.let {
                    videoNoteString.replace(
                        C.MESSAGE_TYPE_DURATION_PLACEHOLDER,
                        it.duration.toString()
                    )
                }
                ?: voice?.let {
                    voiceString.replace(
                        C.MESSAGE_TYPE_DURATION_PLACEHOLDER,
                        it.duration.toString()
                    )
                }
                ?: poll?.let {
                    pollString.replace(
                        C.MESSAGE_TYPE_QUESTION_PLACEHOLDER,
                        it.question
                    )
                }
        }
    }


    fun sendMessageToMinecraft(
        text: String,
        type: String? = null,
        replyToMessage: Message? = null,
        username: String? = null,
        chatTitle: String? = null,
    ) = config?.run {
        minecraftFormat
            .replace(C.MESSAGE_TEXT_PLACEHOLDER, text.escapeEmoji())
            .run {
                replace(C.MESSAGE_REPLY_TO_PLACEHOLDER, replyToMessage?.let {
                    replyToMessageFormat
                        .replace(C.MESSAGE_TYPE_PLACEHOLDER, getTypeString(it) ?: "")
                        .replace(C.CHAT_TITLE_PLACEHOLDER, it.chat.title ?: "<unknown chat>")
                        .replace(C.USERNAME_PLACEHOLDER, it.from?.rawUserMention() ?: "<unknown user>")
                        .replace(C.MESSAGE_TEXT_PLACEHOLDER, it.text ?: it.caption ?: "")
                } ?: "")
            }
            .run {
                replace(C.MESSAGE_TYPE_PLACEHOLDER, type ?: "")
            }
            .run {
                username?.let {
                    replace(C.USERNAME_PLACEHOLDER, it.escapeEmoji())
                } ?: this
            }
            .run {
                chatTitle?.let {
                    replace(C.CHAT_TITLE_PLACEHOLDER, it)
                } ?: this
            }
            .also { server.broadcastMessage(it) }
    }

    suspend fun reload() {
        config = Configuration(this).also { config ->
            if (!config.isEnabled) return
            logger.info(C.INFO.reloading)
            eventHandler?.let { HandlerList.unregisterAll(it) }
            tgBot?.run { stop() }
            tgBot = TgBot(this, config).also { bot ->
                bot.startPolling()
                eventHandler = EventHandler(this, config, bot).also {
                    server.pluginManager.registerEvents(it, this)
                }
            }
            logger.info(C.INFO.reloadComplete)
        }
    }
}
