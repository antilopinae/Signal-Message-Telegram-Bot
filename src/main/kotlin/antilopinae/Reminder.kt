package antilopinae

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Reminder(
    val reminderId: String = UUID.randomUUID().toString(),
    val userChatId: Long,
    val reminderTimestampMillis: Long,
    var isSent: Boolean = false,
    val originalMessageIdToForward: Long? = null,
    val originalChatIdToForwardFrom: Long? = null,
    val messageTextContent: String? = null,
    val forwardedToBotAtMillis: Long
)
