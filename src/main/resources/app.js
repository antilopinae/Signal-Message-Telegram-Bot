document.addEventListener('DOMContentLoaded', function () {
    const tg = window.Telegram.WebApp;
    tg.ready(); // Сообщаем Telegram, что приложение готово и можно показать

    // Получаем параметры из URL, переданные ботом
    const urlParams = new URLSearchParams(window.location.search);
    const userChatId = urlParams.get('user_chat_id');
    const originalMessageId = urlParams.get('original_message_id');
    const originalChatId = urlParams.get('original_chat_id');

    const reminderTimeInput = document.getElementById('reminderTime');
    const setReminderBtn = document.getElementById('setReminderBtn');
    const statusMessageDiv = document.getElementById('statusMessage');

    if (!userChatId || !originalMessageId || !originalChatId) {
        statusMessageDiv.textContent = 'Ошибка: Необходимые данные для установки напоминания отсутствуют. Пожалуйста, попробуйте снова из чата с ботом.';
        statusMessageDiv.style.color = tg.themeParams.destructive_text_color || 'red';
        setReminderBtn.classList.add('hidden'); // Скрываем кнопку, если нет данных
        return;
    }

    // Устанавливаем минимальное значение для datetime-local на текущее время + 1 минута, чтобы нельзя было выбрать прошлое
    const now = new Date();
    now.setMinutes(now.getMinutes() + 1); // Добавляем 1 минуту
    const আইএসওOffset = (new Date()).getTimezoneOffset() * 60000; // смещение в миллисекундах
    const localISOTime = (new Date(now - আইএসওOffset)).toISOString().slice(0,16);
    reminderTimeInput.min = localISOTime;


    setReminderBtn.addEventListener('click', () => {
        const reminderTimeValue = reminderTimeInput.value; // "YYYY-MM-DDTHH:mm" (локальное время)

        if (!reminderTimeValue) {
            tg.showAlert('Пожалуйста, выберите дату и время для напоминания.');
            return;
        }

        // Преобразуем локальное время в UTC timestamp (миллисекунды)
        const localDate = new Date(reminderTimeValue);
        if (isNaN(localDate.getTime())) {
            tg.showAlert('Выбрана некорректная дата или время.');
            return;
        }
         if (localDate.getTime() <= Date.now()) {
            tg.showAlert('Пожалуйста, выберите время в будущем.');
            return;
        }
        const reminderTimestampMillis = localDate.getTime(); // Это уже UTC timestamp, т.к. Date() парсит YYYY-MM-DDTHH:mm как локальное и хранит как UTC.

        const dataToSend = {
            action: "set_reminder",
            user_chat_id: userChatId,
            original_message_id: originalMessageId,
            original_chat_id: originalChatId,
            reminder_timestamp_millis: reminderTimestampMillis
        };

        // Отправляем данные боту
        tg.sendData(JSON.stringify(dataToSend));

        // Не обязательно закрывать сразу, бот пришлет подтверждение
        // Можно показать сообщение в TWA:
        statusMessageDiv.textContent = 'Напоминание отправлено на установку...';
        statusMessageDiv.style.color = tg.themeParams.text_color || 'black';
        // tg.close(); // Можно закрыть TWA после отправки, если бот подтверждает в чате
    });

    // Расширяем WebApp для использования всей высоты
    tg.expand();
});