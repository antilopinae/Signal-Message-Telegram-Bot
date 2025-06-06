document.addEventListener('DOMContentLoaded', function () {
    const tg = window.Telegram.WebApp;
    tg.ready();
    tg.expand();

    const urlParams = new URLSearchParams(window.location.search);
    const userChatId = urlParams.get('user_chat_id');
    const reminderType = urlParams.get('type');
    const eventHint = decodeURIComponent(urlParams.get('event_hint') || "сообщения");
    const forwardedToBotAtSeconds = urlParams.get('forwarded_to_bot_at');

    const originalMessageId = urlParams.get('original_message_id');
    const originalChatId = urlParams.get('original_chat_id');
    const textContent = decodeURIComponent(urlParams.get('text_content') || "");

    const reminderTimeInputEl = document.getElementById('reminderTimeInput'); // Наш новый input
    const setReminderBtn = document.getElementById('setReminderBtn');
    const statusMessageDiv = document.getElementById('statusMessage');
    const messageHintDiv = document.getElementById('messageHint');

    if (messageHintDiv) {
        messageHintDiv.textContent = `Напоминание для: "${eventHint}"`;
    }

    if (!userChatId || !reminderType || !forwardedToBotAtSeconds) {
        statusMessageDiv.textContent = 'Ошибка: Необходимые данные для установки напоминания отсутствуют. Пожалуйста, попробуйте снова из чата с ботом.';
        statusMessageDiv.style.color = tg.themeParams.destructive_text_color || 'red';
        if(setReminderBtn) setReminderBtn.classList.add('hidden');
        return;
    }

    const fpInstance = flatpickr(reminderTimeInputEl, {
        enableTime: true,
        dateFormat: "Y-m-d H:i",
        minDate: "today",
        time_24hr: true,
        locale: "ru",
        minuteIncrement: 1,
        onOpen: function(selectedDates, dateStr, instance) {
            if (tg.colorScheme === 'dark') {
                instance.calendarContainer.classList.add('flatpickr-dark');
            }
        },
        onChange: function(selectedDates, dateStr, instance) {
            if (setReminderBtn) setReminderBtn.disabled = selectedDates.length === 0;
        }
    });

    if (setReminderBtn) {
        setReminderBtn.disabled = true;

        setReminderBtn.addEventListener('click', () => {
            const selectedDates = fpInstance.selectedDates;
            if (selectedDates.length === 0) {
                tg.showAlert('Пожалуйста, выберите дату и время для напоминания.');
                return;
            }

            const selectedDate = selectedDates[0];

            if (selectedDate.getTime() <= Date.now()) {
                tg.showAlert('Пожалуйста, выберите время в будущем.');
                return;
            }
            const reminderTimestampMillis = selectedDate.getTime();

            const dataToSend = {
                action: "set_reminder",
                user_chat_id: userChatId,
                reminder_type: reminderType,
                reminder_timestamp_millis: reminderTimestampMillis,
                forwarded_to_bot_at_millis: parseInt(forwardedToBotAtSeconds) * 1000
            };

            if (reminderType === "forward" && originalMessageId && originalChatId) {
                dataToSend.original_message_id = originalMessageId;
                dataToSend.original_chat_id = originalChatId;
            } else if (reminderType === "text_content") {
                dataToSend.text_content = textContent;
            } else if (reminderType === "forward" && (!originalMessageId || !originalChatId)){
                tg.showAlert("Ошибка: Недостаточно данных для пересылки сообщения.");
                return;
            }


            tg.sendData(JSON.stringify(dataToSend));
            statusMessageDiv.textContent = 'Напоминание отправлено на установку... Окно можно закрыть.';
            setReminderBtn.disabled = true;
        });
    }
});