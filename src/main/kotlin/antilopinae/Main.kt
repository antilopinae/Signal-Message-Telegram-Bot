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
import java.util.Date
import java.util.concurrent.TimeUnit

val remindersList = mutableListOf<Reminder>()
val remindersMutex = Mutex()

fun main() {
    val botToken = System.getenv("SIGNAL_MESSAGE_BOT_TOKEN")
    val baseWebAppUrl = "https://calendar.google.com/calendar/u/0/r"

    val bot = bot {
        token = botToken
        logLevel = LogLevel.Error

        dispatch {
            command("start") {
                val chatId = ChatId.fromId(message.chat.id)
                bot.sendMessage(
                    chatId = chatId,
                    text = "Привет! Я бот-планировщик задач. Просто перешли мне любое сообщение, и я добавлю его в твое расписание."
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
                            val original_message_id: String,
                            val original_chat_id: String,
                            val reminder_timestamp_millis: Long
                        )

                        val request = Json.decodeFromString<ReminderRequestFromTwa>(webAppDataJson!!)

                        if (request.action == "set_reminder") {
                            val newReminder = Reminder(
                                userChatId = request.user_chat_id.toLong(),
                                originalMessageId = request.original_message_id.toLong(),
                                originalChatId = request.original_chat_id.toLong(),
                                reminderTimestampMillis = request.reminder_timestamp_millis
                            )

                            GlobalScope.launch {
                                remindersMutex.withLock {
                                    remindersList.add(newReminder)
                                }
                            }
                            println("Напоминание добавлено: $newReminder")

                            val reminderDate = Date(request.reminder_timestamp_millis)
                            bot.sendMessage(
                                chatId = ChatId.fromId(request.user_chat_id.toLong()),
                                text = "✅ Напоминание успешно установлено на $reminderDate"
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

    val originalMessageId = forwardedMessageToBot.forwardFromMessageId
    val originalChatIdFrom = forwardedMessageToBot.forwardFromChat?.id
        ?: forwardedMessageToBot.forwardFrom?.id

    if (originalMessageId == null || originalChatIdFrom == null) {
        println("Ошибка: не удалось получить ID оригинального сообщения или ID исходного чата для сообщения ${forwardedMessageToBot.messageId}.")
        env.bot.sendMessage(
            chatId = userChatId,
            text = "К сожалению, не удалось обработать это пересланное сообщение для установки напоминания (отсутствуют данные об источнике пересылки). Попробуйте переслать сообщение из другого источника."
        )
        return
    }

    val eventHint = forwardedMessageToBot.text ?: forwardedMessageToBot.caption ?: "Пересланный контент"

    // user_chat_id - чат пользователя с ботом, куда присылать подтверждения и само напоминание
    // original_message_id - ID сообщения, которое нужно будет переслать в качестве напоминания
    // original_chat_id - ID чата, ИЗ КОТОРОГО нужно будет переслать это сообщение
    val webAppQueryParameters =
        "user_chat_id=${userChatId.id}" +
                "&original_message_id=$originalMessageId" +
                "&original_chat_id=$originalChatIdFrom"

    val finalWebAppUrl = "$baseWebAppUrl?$webAppQueryParameters"
    println("URL для WebApp (с ID для напоминания): $finalWebAppUrl")

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
                            // Помечаем как "в процессе отправки" или удаляем сразу,
                            // чтобы не отправить повторно при быстрой проверке.
                            // Для простоты, сначала соберем, потом обработаем и удалим/пометим.
                        }
                    }
                }

                for (reminder in dueReminders) {
                    println("Отправка напоминания: $reminder")

                    val forwardResult = bot.forwardMessage(
                        chatId = ChatId.fromId(reminder.userChatId),
                        fromChatId = ChatId.fromId(reminder.originalChatId),
                        messageId = reminder.originalMessageId,
                        disableNotification = false
                    )

                    if (!forwardResult.isSuccess) {
                        println("Не удалось переслать сообщение для напоминания ${reminder.reminderId}: ${forwardResult.get().text}")
                    }

                    bot.sendMessage(
                        chatId = ChatId.fromId(reminder.userChatId),
                        text = "🔔 Напоминаю об этом!",
                    )

                    remindersMutex.withLock {
                        remindersList.find { it.reminderId == reminder.reminderId }?.isSent = true
                    }
                    println("Напоминание ${reminder.reminderId} обработано.")
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