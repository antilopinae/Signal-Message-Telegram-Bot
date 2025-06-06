package antilopinae;

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.handlers.MessageHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.WebAppInfo
import com.github.kotlintelegrambot.logging.LogLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.TimeUnit

val remindersList = mutableListOf<Reminder>()
val remindersMutex = Mutex()

fun main() {
    val botToken = System.getenv("SIGNAL_MESSAGE_BOT_TOKEN")
    val baseWebAppUrl = "https://antilopinae.github.io/Signal-Message-Telegram-Bot/"

    val bot = bot {
        token = botToken
        logLevel = LogLevel.Error

        dispatch {
            command("start") {
                val chatId = ChatId.fromId(message.chat.id)
                bot.sendMessage(
                    chatId = chatId,
                    text = "Привет \uD83D\uDC4B\n" +
                            "\n" +
                            "Я — бот, который превращает пересланные сообщения в напоминания. Просто перешли мне любое сообщение, а я помогу тебе настроить, когда и как о нём напомнить."
                )
                println("Обработана команда /start для чата ${chatId.id}")
            }

            message {
                val incomingMessage = message
                val currentChatId = ChatId.fromId(incomingMessage.chat.id)

                val webAppData = message.webAppData
                if (webAppData != null) {
                    val webAppDataJson = webAppData.data
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

                        val request = Json { ignoreUnknownKeys = true }.decodeFromString<ReminderRequestFromTwa>(webAppDataJson.toString()) // Json { ignoreUnknownKeys = true }

                        if (request.action == "set_reminder") {
                            val newReminder = Reminder(
                                userChatId = request.user_chat_id.toLong(),
                                reminderTimestampMillis = request.reminder_timestamp_millis,
                                forwardedToBotAtMillis = request.forwarded_to_bot_at_millis, // Сохраняем это время
                                originalMessageIdToForward = if (request.reminder_type == "forward") request.original_message_id?.toLongOrNull() else null,
                                originalChatIdToForwardFrom = if (request.reminder_type == "forward") request.original_chat_id?.toLongOrNull() else null,
                                messageTextContent = if (request.reminder_type == "text_content") request.text_content else null
                            )

                            val reminderDate = Date(request.reminder_timestamp_millis)
                            var confirmationText = "✅ Напоминание успешно установлено на $reminderDate"
                            if (request.reminder_type == "text_content" && newReminder.messageTextContent.isNullOrEmpty()) {
                                confirmationText += " (текст сообщения не был захвачен, будет общее напоминание)"
                            }
                            bot.sendMessage(
                                chatId = ChatId.fromId(request.user_chat_id.toLong()),
                                text = confirmationText
                            )
                        }
                    } catch (e: Exception) {
                        println("Ошибка обработки данных от Web App: ${e.message}")
                        e.printStackTrace()
                        // val possiblyUserChatId = Json.parseToJsonElement(webAppDataJson).jsonObject["user_chat_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        // if (possiblyUserChatId != null) {
                        //     bot.sendMessage(ChatId.fromId(possiblyUserChatId), "Произошла ошибка при установке напоминания.")
                        // }
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
                        text = "Пожалуйста, перешлите мне сообщение, которое вы хотите сохранить в календарь."
                    )
                }
                else {
                    println("Получено другое (не текст, не пересланное) сообщение от ${incomingMessage.chat.username ?: "N/A"} (ID: ${currentChatId.id})")
                    bot.sendMessage(
                        chatId = currentChatId,
                        text = "Пожалуйста, перешлите мне сообщение для добавления в календарь."
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
            webAppQueryParameters += "&text_content=${URLEncoder.encode(capturedTextContent.take(500), StandardCharsets.UTF_8.toString())}"
        }
        println("Готовим TWA для ОТПРАВКИ ТЕКСТА (пересылка невозможна). Текст: $capturedTextContent")
    }

    val finalWebAppUrl = "$baseWebAppUrl?$webAppQueryParameters"
    println("URL для WebApp: $finalWebAppUrl")

    val webAppInfo = WebAppInfo(url = finalWebAppUrl)
    val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
        listOf(
            InlineKeyboardButton.WebApp("🗓️ Настроить напоминание", webAppInfo)
        )
    )

    env.bot.sendMessage(
        chatId = userChatId,
        text = "Нажмите, чтобы настроить напоминание для: '${eventHint.take(50)}...'",
        replyToMessageId = forwardedMessageToBot.messageId,
        replyMarkup = inlineKeyboardMarkup
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
                    while(iterator.hasNext()){
                        val reminder = iterator.next()
                        if (reminder.reminderTimestampMillis <= now && !reminder.isSent) {
                            dueReminders.add(reminder)
                        }
                    }
                }

                for (reminder in dueReminders) {
                    if (reminder.originalMessageIdToForward != null && reminder.originalChatIdToForwardFrom != null) {
                        println("Отправка напоминания (пересылка): $reminder")
                        val forwardResult = bot.forwardMessage(
                            chatId = ChatId.fromId(reminder.userChatId),
                            fromChatId = ChatId.fromId(reminder.originalChatIdToForwardFrom),
                            messageId = reminder.originalMessageIdToForward,
                            disableNotification = false
                        )
                        if (!forwardResult.isSuccess) {
                            println("Не удалось переслать сообщение для напоминания ${reminder.reminderId}: ${forwardResult.get().text ?: "Нет описания ошибки"}")
                            val fallbackText = "🔔 Напоминаю о сообщении, которое вы переслали ${Date(reminder.forwardedToBotAtMillis)}." +
                                    (if (!reminder.messageTextContent.isNullOrEmpty()) "\n\nСодержимое: «${reminder.messageTextContent.take(200)}...»" else "")
                            bot.sendMessage(ChatId.fromId(reminder.userChatId), text = fallbackText)
                        } else {
                            bot.sendMessage(
                                chatId = ChatId.fromId(reminder.userChatId),
                                text = "🔔 Напоминаю об этом! (сообщение выше)"
                                // replyToMessageId = forwardResult.first?.result?.messageId // Можно отвечать на пересланное
                            )
                        }
                    } else {
                        println("Отправка напоминания (текст): $reminder")
                        val textToSend = "🔔 Напоминаю о сообщении, которое вы переслали боту ${Date(reminder.forwardedToBotAtMillis)}." +
                                (if (!reminder.messageTextContent.isNullOrEmpty()) "\n\nЕго текст был: «${reminder.messageTextContent.take(3000)}»" else "\n(Текстовое содержимое не было сохранено).") // Увеличил лимит
                        bot.sendMessage(
                            chatId = ChatId.fromId(reminder.userChatId),
                            text = textToSend
                        )
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