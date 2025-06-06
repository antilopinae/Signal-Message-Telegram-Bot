document.addEventListener('DOMContentLoaded', function () {
    const tg = window.Telegram.WebApp;
    tg.ready();

    const urlParams = new URLSearchParams(window.location.search);
    const userChatId = urlParams.get('user_chat_id');
    const reminderType = urlParams.get('type'); // "forward" или "text_content"
    const eventHint = decodeURIComponent(urlParams.get('event_hint') || "сообщения");
    const forwardedToBotAtSeconds = urlParams.get('forwarded_to_bot_at'); // секунды

    // Для "forward"
    const originalMessageId = urlParams.get('original_message_id');
    const originalChatId = urlParams.get('original_chat_id');
    // Для "text_content"
    const textContent = decodeURIComponent(urlParams.get('text_content') || "");


    const reminderTimeInput = document.getElementById('reminderTime');
    const setReminderBtn = document.getElementById('setReminderBtn');
    const statusMessageDiv = document.getElementById('statusMessage');
    const messageHintDiv = document.getElementById('messageHint'); // Добавь <div id="messageHint"></div> в HTML

    if (messageHintDiv) {
        messageHintDiv.textContent = `Напоминание для: ${eventHint}`;
    }

    if (!userChatId || !reminderType || !forwardedToBotAtSeconds) {
        statusMessageDiv.textContent = 'Ошибка: Необходимые данные отсутствуют.';
        setReminderBtn.classList.add('hidden');
        return;
    }

    // ... (код установки reminderTimeInput.min) ...
    const now = new Date();
    now.setMinutes(now.getMinutes() + 1);
    const আইএসওOffset = (new Date()).getTimezoneOffset() * 60000;
    const localISOTime = (new Date(now - আইএসওOffset)).toISOString().slice(0,16);
    reminderTimeInput.min = localISOTime;


    setReminderBtn.addEventListener('click', () => {
        const reminderTimeValue = reminderTimeInput.value;
        if (!reminderTimeValue) {
            tg.showAlert('Пожалуйста, выберите дату и время.');
            return;
        }
        const localDate = new Date(reminderTimeValue);
        if (isNaN(localDate.getTime()) || localDate.getTime() <= Date.now()) {
            tg.showAlert('Выберите корректное время в будущем.');
            return;
        }
        const reminderTimestampMillis = localDate.getTime();

        const dataToSend = {
            action: "set_reminder",
            user_chat_id: userChatId,
            reminder_type: reminderType,
            reminder_timestamp_millis: reminderTimestampMillis,
            forwarded_to_bot_at_millis: parseInt(forwardedToBotAtSeconds) * 1000 // преобразуем в миллисекунды
        };

        if (reminderType === "forward") {
            dataToSend.original_message_id = originalMessageId;
            dataToSend.original_chat_id = originalChatId;
        } else if (reminderType === "text_content") {
            dataToSend.text_content = textContent; // Передаем текст обратно
        }

        tg.sendData(JSON.stringify(dataToSend));
        statusMessageDiv.textContent = 'Напоминание отправлено на установку...';
    });
    tg.expand();
});