package me.kirillgrachev.query.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единственное исключение библиотеки.
 *
 * <p>Непроверяемое (RuntimeException) по двум причинам. Первая: оборачивание
 * SQLException в проверяемое исключение не добавляет информации — вызывающая
 * сторона всё равно не может осмысленно обработать «ошибку базы» на месте, ей
 * нужен код и контекст. Вторая: проверяемое исключение в каждом сигнатурном
 * методе заставило бы оборачивать каждый вызов в try-catch, что ровно тот
 * бойлерплейт, от которого библиотека должна избавлять.
 *
 * <p>Несёт {@link ErrorCode} и произвольный контекст в виде карты. Контекст —
 * то, чего не хватает в тексте сообщения: offending-идентификатор, номер
 * параметра, собранный SQL, имя диалекта. Сообщение остаётся стабильным и
 * пригодным для поиска по логам, а детали живут в структуре и доступны
 * программно через {@link #getContextValue(String, Class)}.
 *
 * <p>Создаётся через статические фабрики {@code of(...)}, конструктор скрыт:
 * так невозможно создать исключение без кода, а значит и без категории.
 */
public final class QueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Код ошибки из реестра {@link ErrorCode}. */
    private final ErrorCode code;

    /** Контекст: произвольные пары ключ-значение, относящиеся к месту сбоя. */
    private final Map<String, Object> context;

    private QueryException(ErrorCode code,
                           String message,
                           Map<String, Object> context,
                           Throwable cause) {

        super(message, cause);
        this.code = code;
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(context));

    }

    public static @NotNull QueryException of(@NotNull ErrorCode code) {

        return new QueryException(code, code.getDefaultMessage(), Map.of(), null);

    }

    public static @NotNull QueryException of(@NotNull ErrorCode code, @NotNull String message) {

        return new QueryException(code, message, Map.of(), null);

    }

    public static @NotNull QueryException of(@NotNull ErrorCode code,
                                             @NotNull String message,
                                             @NotNull Throwable cause) {

        return new QueryException(code, message, Map.of(), cause);

    }

    /**
     * Перегрузка с контекстом, но без причины.
     *
     * <p>Отдельная сигнатура нужна не для красоты: без неё вызов
     * {@code of(code, message, Map.of(...))} матчится на перегрузку с
     * {@code Throwable} третьим аргументом, и компилятор сообщает о несовместимости
     * Map и Throwable вместо очевидного «нет такой перегрузки».
     */
    public static @NotNull QueryException of(@NotNull ErrorCode code,
                                             @NotNull String message,
                                             @NotNull Map<String, Object> context) {

        return new QueryException(code, message, context, null);

    }

    public static @NotNull QueryException of(@NotNull ErrorCode code,
                                             @NotNull String message,
                                             @NotNull Map<String, Object> context,
                                             @Nullable Throwable cause) {

        return new QueryException(code, message, context, cause);

    }

    public @NotNull ErrorCode getCode() {

        return code;

    }

    public @NotNull ErrorCategory getCategory() {

        return code.getCategory();

    }

    public @NotNull Map<String, Object> getContext() {

        return context;

    }

    /**
     * Возвращает значение контекста, приведённое к ожидаемому типу.
     *
     * <p>Приведение проверяется явно, а не отдаётся на откуп вызывающей стороне:
     * {@code (Integer) e.getContext().get("index")} при несовпадении типа даёт
     * ClassCastException уже в коде обработчика, вдали от места сбоя. Здесь
     * несовпадение возвращает {@code null}, и его легко отличить от отсутствия ключа.
     *
     * @param key   ключ контекста
     * @param type  ожидаемый тип значения
     * @param <T>   тип значения
     * @return значение, либо {@code null}, если ключа нет или тип не совпал
     */
    public <T> @Nullable T getContextValue(@NotNull String key, @NotNull Class<T> type) {

        Object value = context.get(key);

        return type.isInstance(value) ? type.cast(value) : null;

    }

    /**
     * Текстовое представление включает код и контекст.
     *
     * <p>Формат намеренно однострочный: исключение чаще всего печатается одной
     * строкой в лог, и многострочный toString разрывает её на части, которые
     * невозможно связать с одним событием.
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder(getClass().getName())
                .append('[').append(code.name()).append("] ")
                .append(getMessage());

        if (!context.isEmpty()) {
            sb.append(" context=").append(context);
        }

        return sb.toString();

    }

}
