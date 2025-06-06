package antilopinae

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable // Для kotlinx.serialization
data class Reminder(
    val reminderId: String = UUID.randomUUID().toString(),
    val userChatId: Long,
    val originalMessageId: Long,
    val originalChatId: Long,
    val reminderTimestampMillis: Long,
    var isSent: Boolean = false
)
