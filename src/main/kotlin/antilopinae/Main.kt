package antilopinae;

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.handlers.MessageHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.WebAppInfo
import com.github.kotlintelegrambot.logging.LogLevel
import com.github.kotlintelegrambot.webhook
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

val remindersList = mutableListOf<Reminder>()
val remindersMutex = Mutex()

val dateFormatter = SimpleDateFormat("dd MMM yyyy 'в' HH:mm z", Locale("ru"))
// init { dateFormatter.timeZone = TimeZone.getTimeZone("Europe/Moscow") } // Пример установки таймзоны

fun main() {
    val botToken = System.getenv("SIGNAL_MESSAGE_BOT_TOKEN") ?: run {
        println("Ошибка: Переменная окружения SIGNAL_MESSAGE_BOT_TOKEN не установлена.")
        return
    }
    val baseWebAppUrl = "https://antilopinae.github.io/Signal-Message-Telegram-Bot/"

    val bot = bot {
        token = botToken
        logLevel = LogLevel.Error

        dispatch {
            command("start") {
                val chatId = ChatId.fromId(message.chat.id)
                bot.sendMessage(
                    chatId = chatId,
                    text = "Привет 👋\n\n" +
                            "Я — бот, который превращает пересланные сообщения в напоминания. " +
                            "Просто перешли мне любое сообщение, а я помогу тебе настроить, когда и как о нём напомнить.\n\n" +
                            "Основные команды:\n" +
                            "/help - Инструкция и поддержка\n" +
                            "/status - Посмотреть активные напоминания\n" +
                            "/unsubscribe - Отключить все напоминания",
                    parseMode = ParseMode.MARKDOWN
                )
                println("Обработана команда /start для чата ${chatId.id}")
            }

            command("help") {
                val chatId = ChatId.fromId(message.chat.id)
                bot.sendMessage(
                    chatId = chatId,
                    text = "❓ **Как пользоваться ботом:**\n\n" +
                            "1. **Перешлите** мне любое сообщение, о котором хотите получить напоминание.\n" +
                            "2. Я предложу кнопку **\"🗓️ Настроить напоминание\"**. Нажмите ее.\n" +
                            "3. В открывшемся окне выберите **дату и время** для напоминания.\n" +
                            "4. Нажмите **\"Установить напоминание\"** в этом окне.\n" +
                            "5. Я пришлю подтверждение, и в указанное время напомню о сообщении.\n\n" +
                            "📜 **Доступные команды:**\n" +
                            "/start - Начало работы с ботом\n" +
                            "/help - Эта инструкция\n" +
                            "/status - Проверить ваши активные напоминания\n" +
                            "/unsubscribe - Удалить все ваши активные напоминания\n" +
                            "/settings - Настройки (в разработке)\n\n" +
                            "Если возникли проблемы или есть предложения, сообщите разработчику @antilopinae.",
                    parseMode = ParseMode.MARKDOWN
                )
                println("Обработана команда /help для чата ${chatId.id}")
            }

            command("settings") {
                val chatId = ChatId.fromId(message.chat.id)
                bot.sendMessage(
                    chatId = chatId,
                    text = "⚙️ Настройки пока находятся в разработке. Следите за обновлениями!"
                )
                println("Обработана команда /settings для чата ${chatId.id}")
            }

            command("status") {
                val userChatId = message.chat.id
                GlobalScope.launch {
                    val userChatId = message.chat.id
                    println("--- КОМАНДА /status ВЫЗВАНА ДЛЯ ЧАТА $userChatId ---")
                    GlobalScope.launch {
                        val currentRemindersSnapshot = remindersMutex.withLock {
                            println("--- /status: Содержимое remindersList ПЕРЕД фильтрацией (всего ${remindersList.size}) ---")
                            remindersList.forEachIndexed { index, rem -> println("[$index]: $rem") }
                            remindersList.toList()
                        }

                        val userActiveReminders = currentRemindersSnapshot
                            .filter { it.userChatId == userChatId && !it.isSent }
                            .sortedBy { it.reminderTimestampMillis }

                        println("--- /status: Найдено ${userActiveReminders.size} активных напоминаний для пользователя $userChatId ---")
                        userActiveReminders.forEachIndexed { index, rem -> println("Активное [$index]: $rem") }

                        if (userActiveReminders.isEmpty()) {
                            bot.sendMessage(ChatId.fromId(userChatId), "У вас нет активных напоминаний. ✨")
                        } else {
                            val remindersText = userActiveReminders.mapIndexed { index, reminder ->
                                val reminderTimeStr = dateFormatter.format(Date(reminder.reminderTimestampMillis))
                                var contentPreview = reminder.messageTextContent?.take(30)
                                    ?: reminder.originalMessageIdToForward?.let { "исходное сообщение" }
                                    ?: "сообщение"
                                if (contentPreview.length == 30 && (reminder.messageTextContent?.length
                                        ?: 0) > 30
                                ) contentPreview += "..."

                                "${index + 1}. Напоминание для \"$contentPreview\" на $reminderTimeStr"
                            }.joinToString("\n\n")

                            bot.sendMessage(
                                chatId = ChatId.fromId(userChatId),
                                text = "🗓️ **Ваши активные напоминания:**\n\n$remindersText"
                            )
                        }
                    }
                    println("Обработана команда /status для чата ${userChatId}")
                }
            }

            command("unsubscribe") {
                val userChatId = message.chat.id
                var removedCount = 0
                GlobalScope.launch {
                    remindersMutex.withLock {
                        val initialSize = remindersList.size
                        remindersList.removeIf { it.userChatId == userChatId && !it.isSent }
                        removedCount = initialSize - remindersList.size
                    }

                    if (removedCount > 0) {
                        bot.sendMessage(ChatId.fromId(userChatId), "✅ Все ваши $removedCount активных напоминаний были удалены.")
                    } else {
                        bot.sendMessage(ChatId.fromId(userChatId), "У вас не было активных напоминаний для удаления.")
                    }
                    println("Обработана команда /unsubscribe для чата ${userChatId}, удалено $removedCount напоминаний.")
                }
            }

            message {
                val incomingMessage = message
                val currentChatId = ChatId.fromId(incomingMessage.chat.id)
                val webAppDataFromMessage = incomingMessage.webAppData

                if (incomingMessage.text?.startsWith("/") == true) {
                    return@message
                }

                if (webAppDataFromMessage != null) {
                    val webAppDataJson = webAppDataFromMessage.data
                    println("Получены данные от Web App: $webAppDataJson")

                    try {
                        @Serializable
                        data class ReminderRequestFromTwa(
                            val action: String,
                            val user_chat_id: String,
                            val reminder_type: String,
                            val reminder_timestamp_millis: Long,
                            val forwarded_to_bot_at_millis: Long,
                            val original_message_id: String? = null,
                            val original_chat_id: String? = null,
                            val text_content: String? = null
                        )

                        val request = Json { ignoreUnknownKeys = true }.decodeFromString<ReminderRequestFromTwa>(webAppDataJson.toString())

                        if (request.action == "set_reminder") {
                            val newReminder = Reminder(
                                userChatId = request.user_chat_id.toLong(),
                                reminderTimestampMillis = request.reminder_timestamp_millis,
                                forwardedToBotAtMillis = request.forwarded_to_bot_at_millis,
                                originalMessageIdToForward = if (request.reminder_type == "forward") request.original_message_id?.toLongOrNull() else null,
                                originalChatIdToForwardFrom = if (request.reminder_type == "forward") request.original_chat_id?.toLongOrNull() else null,
                                messageTextContent = if (request.reminder_type == "text_content") request.text_content else null
                            )

                            GlobalScope.launch {
                                remindersMutex.withLock {
                                    remindersList.add(newReminder)
                                    println("--- НАПОМИНАНИЕ ДОБАВЛЕНО В СПИСОК ---")
                                    println("Всего напоминаний в списке: ${remindersList.size}")
                                    remindersList.forEachIndexed { index, rem -> println("[$index]: $rem") }
                                }
                            }
                            println("Напоминание добавлено: $newReminder")

                            val reminderDate = Date(request.reminder_timestamp_millis)
                            var confirmationText = "✅ Напоминание успешно установлено на ${dateFormatter.format(reminderDate)}"
                            if (request.reminder_type == "text_content" && newReminder.messageTextContent.isNullOrEmpty()) {
                                confirmationText += " (текст сообщения не был захвачен, будет общее напоминание)"
                            }
                            bot.sendMessage(
                                chatId = ChatId.fromId(request.user_chat_id.toLong()),
                                text = confirmationText,
                                replyMarkup = ReplyKeyboardRemove()
                            )
//                            this.bot.editMessageReplyMarkup(
//                                chatId = ChatId.fromId(request.user_chat_id.toLong()),
//                                messageId = incomingMessage.messageId,
//                                replyMarkup = null
//                            )
                        }
                    } catch (e: Exception) {
                        println("Ошибка обработки данных от Web App: ${e.message}")
                        e.printStackTrace()
                        bot.sendMessage(
                            ChatId.fromId(message.chat.id),
                            text = "Произошла ошибка при установке напоминания через веб-приложение. Попробуйте еще раз."
                        )
                    }
                }
                else if (incomingMessage.forwardDate != null || incomingMessage.forwardFrom != null || incomingMessage.forwardFromChat != null) {
                    println("ПОЛУЧЕНО ПЕРЕСЛАННОЕ СООБЩЕНИЕ (ID: ${incomingMessage.messageId}) от ${incomingMessage.chat.username ?: "N/A"}")
                    handleForwardedContent(this, baseWebAppUrl)
                }
                else if (incomingMessage.text != null) {
                    println("Получено ОБЫЧНОЕ ТЕКСТОВОЕ сообщение от ${incomingMessage.chat.username ?: "N/A"} (ID: ${currentChatId.id}): '${incomingMessage.text}'")
                    bot.sendMessage(
                        chatId = currentChatId,
                        text = "Пожалуйста, перешлите мне сообщение, которое вы хотите сохранить в календарь. Используйте /help для инструкции."
                    )
                }
                else {
                    println("Получено другое (не текст, не пересланное, не webapp data) сообщение от ${incomingMessage.chat.username ?: "N/A"} (ID: ${currentChatId.id})")
                    bot.sendMessage(
                        chatId = currentChatId,
                        text = "Пожалуйста, перешлите мне сообщение для добавления в календарь. Используйте /help для инструкции."
                    )
                }
            }
        }
    }

    bot.startPolling()
    startReminderScheduler(bot)
    println("Бот успешно запущен и ожидает сообщений.")
}

private fun handleForwardedContent(env: MessageHandlerEnvironment, baseWebAppUrl: String) {
    val forwardedMessageToBot = env.message
    val userChatId = ChatId.fromId(forwardedMessageToBot.chat.id)

    val originalMessageIdForForward = forwardedMessageToBot.forwardFromMessageId
    val originalChatIdFromForForward = forwardedMessageToBot.forwardFromChat?.id
        ?: forwardedMessageToBot.forwardFrom?.id

    val eventHint = forwardedMessageToBot.text ?: forwardedMessageToBot.caption ?: "Пересланный контент"
    val capturedTextContent = forwardedMessageToBot.text ?: forwardedMessageToBot.caption

    var webAppQueryParameters = "user_chat_id=${userChatId.id}" +
            "&event_hint=${URLEncoder.encode(eventHint.take(100), StandardCharsets.UTF_8.toString())}" +
            "&forwarded_to_bot_at=${forwardedMessageToBot.date}"

    if (originalMessageIdForForward != null && originalChatIdFromForForward != null) {
        webAppQueryParameters += "&type=forward" +
                "&original_message_id=$originalMessageIdForForward" +
                "&original_chat_id=$originalChatIdFromForForward"
        println("Готовим TWA для ПЕРЕСЫЛКИ сообщения ID $originalMessageIdForForward из чата $originalChatIdFromForForward")
    } else {
        webAppQueryParameters += "&type=text_content"
        if (capturedTextContent != null) {
            webAppQueryParameters += "&text_content=${URLEncoder.encode(capturedTextContent.take(400), StandardCharsets.UTF_8.toString())}"
        }
        println("Готовим TWA для ОТПРАВКИ ТЕКСТА (пересылка невозможна). Текст захвачен: ${capturedTextContent != null}")
    }

    val finalWebAppUrl = "$baseWebAppUrl?$webAppQueryParameters"
    println("URL для WebApp: $finalWebAppUrl")

    val webAppInfo = WebAppInfo(url = finalWebAppUrl)
//    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
//        listOf(
//            InlineKeyboardButton.WebApp("🗓️ Настроить напоминание", webAppInfo)
//        )
//    )
//
//    env.bot.sendMessage(
//        chatId = userChatId,
//        text = "Нажмите, чтобы настроить напоминание для: '${eventHint.take(50)}...'",
//        replyToMessageId = forwardedMessageToBot.messageId,
//        replyMarkup = inlineKeyboardMarkup
//    )

    val replyKeyboardMarkup = KeyboardReplyMarkup(
        keyboard = listOf(listOf(KeyboardButton(
            text = "🗓️ Настроить напоминание",
            webApp = webAppInfo
        ))),
        resizeKeyboard = true,
        oneTimeKeyboard = true
    )

    env.bot.sendMessage(
        chatId = userChatId,
        text = "Нажмите кнопку ниже, чтобы настроить напоминание для: '${eventHint.take(50)}'",
        replyToMessageId = forwardedMessageToBot.messageId,
        replyMarkup = replyKeyboardMarkup
    )
}

fun startReminderScheduler(bot: Bot) {
    GlobalScope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                val now = System.currentTimeMillis()
                val dueReminders = mutableListOf<Reminder>()

                remindersMutex.withLock {
                    val iterator = remindersList.iterator()
                    while (iterator.hasNext()) {
                        val reminder = iterator.next()
                        if (reminder.reminderTimestampMillis <= now && !reminder.isSent) {
                            dueReminders.add(reminder)
                        }
                    }
                }

                for (reminder in dueReminders) {
                    var reminderSuccessfullyHandled = false

                    if (reminder.originalMessageIdToForward != null && reminder.originalChatIdToForwardFrom != null) {
                        println("Отправка напоминания (пересылка): $reminder")
                        val forwardResult = bot.forwardMessage(
                            chatId = ChatId.fromId(reminder.userChatId),
                            fromChatId = ChatId.fromId(reminder.originalChatIdToForwardFrom),
                            messageId = reminder.originalMessageIdToForward,
                            disableNotification = false
                        )
                        if (forwardResult.isSuccess) {
                            reminderSuccessfullyHandled = true
                            bot.sendMessage(
                                chatId = ChatId.fromId(reminder.userChatId),
                                text = "🔔 Напоминаю об этом! (сообщение выше)",
                                replyToMessageId = forwardResult.get().messageId
                            )
                        } else {
                            println("Не удалось переслать сообщение для напоминания ${reminder.reminderId}: ${forwardResult.get().text ?: "Нет описания ошибки"}")
                            val fallbackText = "🔔 Напоминаю о сообщении, которое вы переслали ${dateFormatter.format(Date(reminder.forwardedToBotAtMillis))}." +
                                    (if (!reminder.messageTextContent.isNullOrEmpty()) "\n\nСодержимое: «${reminder.messageTextContent.take(200)}...»" else "\n(Не удалось переслать оригинал. Текстовое содержимое не было сохранено для этого типа напоминания, если это был не просто текст).")
                            bot.sendMessage(ChatId.fromId(reminder.userChatId), text = fallbackText)
                            reminderSuccessfullyHandled = true
                        }
                    } else {
                        println("Отправка напоминания (текст): $reminder")
                        val textToSend = "🔔 Напоминаю о сообщении, которое вы переслали боту ${dateFormatter.format(Date(reminder.forwardedToBotAtMillis))}." +
                                (if (!reminder.messageTextContent.isNullOrEmpty()) "\n\nЕго текст был: «${reminder.messageTextContent}»" else "\n(Текстовое содержимое не было сохранено).")
                        bot.sendMessage(
                            chatId = ChatId.fromId(reminder.userChatId),
                            text = textToSend
                        )
                        reminderSuccessfullyHandled = true
                    }

                    if (reminderSuccessfullyHandled) {
                        remindersMutex.withLock {
                            remindersList.find { it.reminderId == reminder.reminderId }?.isSent = true
                        }
                        println("Напоминание ${reminder.reminderId} обработано.")
                    }
                }

                remindersMutex.withLock {
                    remindersList.removeIf { it.isSent }
                }

            } catch (e: Exception) {
                println("Ошибка в планировщике напоминаний: ${e.message}")
                e.printStackTrace()
            }
            delay(TimeUnit.SECONDS.toMillis(10))
        }
    }
    println("Планировщик напоминаний запущен.")
}