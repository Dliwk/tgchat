package su.dromanov.tgchat

object Constants {
    const val configFilename = "config.yml"
    object WARN {
        const val noConfigWarning = "No config file found! Writing default config to config.yml."
        const val noToken = "Bot token must be defined."
        const val noUsername = "Bot username must be defined."
    }
    object INFO {
        const val reloading = "Reloading..."
        const val reloadComplete = "Reload completed."
    }
    object TIMES_OF_DAY {
        const val day = "\uD83C\uDFDE Day"
        const val sunset = "\uD83C\uDF06 Sunset"
        const val night = "\uD83C\uDF03 Night"
        const val sunrise = "\uD83C\uDF05 Sunrise"
    }
    const val USERNAME_PLACEHOLDER = "%username%"
    const val MESSAGE_TEXT_PLACEHOLDER = "%message%"
    const val CHAT_TITLE_PLACEHOLDER = "%chat%"
    const val MESSAGE_TYPE_PLACEHOLDER = "%type%"
    const val MESSAGE_TYPE_FILENAME_PLACEHOLDER = "%filename%"
    const val MESSAGE_TYPE_EMOJI_PLACEHOLDER = "%emoji%"
    const val MESSAGE_TYPE_DURATION_PLACEHOLDER = "%duration%"
    const val MESSAGE_TYPE_QUESTION_PLACEHOLDER = "%question%"
    const val MESSAGE_REPLY_TO_PLACEHOLDER = "%reply%"
    object COMMANDS {
        const val PLUGIN_RELOAD = "tgchat_reload"
    }
    object COMMAND_DESC {
        const val timeDesc = "Get time on server"
        const val onlineDesc = "Get players online"
        const val chatIDDesc = "Get current chat id"
        const val cmdDesc = "Run minecraft command"
    }
}
