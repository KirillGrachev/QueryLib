package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Валидированное имя таблицы, колонки или схемы.
 *
 * <p>Смысл типа — сделать невозможным класс ошибок, а не проверить одно место.
 * В исходной библиотеке валидация вызывалась вручную в каждой фабрике, и ровно в
 * одном месте её забыли: {@code Select.from(database, table)} проверял только
 * имя таблицы, после чего имя базы подставлялось в кавычки как есть. Это
 * полноценная SQL-инъекция, и тест её не ловил, потому что передавал литерал.
 *
 * <p>Здесь имя попадает в запрос ТОЛЬКО через {@code Identifier}, а валидация
 * выполняется в единственном конструкторе. Пропустить её нельзя, не удалив сам
 * тип: новый метод билдера физически не имеет способа принять сырую строку.
 *
 * <p>Правила валидации сознательно опираются на экранирование, а не на запрет
 * символов. Внутри кавычек диалекта безопасно всё, что можно экранировать
 * удвоением символа кавычки, поэтому запрещены только:
 * <ul>
 *   <li>пустое и blank-имя — почти всегда ошибка вызывающей стороны;</li>
 *   <li>управляющие символы (включая {@code \0}, {@code \n}, {@code \r}) — NUL
 *       обрывает строку в нативном слое драйвера, а переводы строк ломают
 *       логирование запроса и позволяют прятать SQL в многострочных конструкциях;</li>
 *   <li>длина больше {@value #MAX_LENGTH} — выше лимита любого распространённого
 *       движка (64 у MySQL, 128 у PostgreSQL и Oracle), то есть имя заведомо не
 *       является настоящим идентификатором.</li>
 * </ul>
 *
 * <p>Неизменяемый: экземпляр можно безопасно кэшировать и переиспользовать между
 * потоками, чем исходная библиотека похвастаться не могла — её билдеры хранили
 * массивы параметров по ссылке.
 */
public final class Identifier {

    /** Максимальная длина имени. 128 покрывает PostgreSQL и Oracle, у MySQL лимит 64. */
    public static final int MAX_LENGTH = 128;

    /** Собственно имя, без квалификатора. */
    private final String name;

    /** Необязательный квалификатор: схема или база данных. */
    private final @Nullable Identifier qualifier;

    private Identifier(String name, @Nullable Identifier qualifier) {
        this.name = name;
        this.qualifier = qualifier;
    }

    /**
     * Создаёт идентификатор из имени.
     *
     * @param raw имя таблицы, колонки или схемы
     * @return валидированный идентификатор
     * @throws QueryException с кодом {@link ErrorCode#INVALID_IDENTIFIER}, если имя недопустимо
     */
    public static @NotNull Identifier of(@Nullable String raw) {
        return new Identifier(validate(raw, "name"), null);
    }

    /**
     * Создаёт квалифицированный идентификатор {@code schema.name}.
     *
     * <p>Оба компонента валидируются. В исходной библиотеке двухаргументные
     * фабрики валидировали оба имени, а {@code Select.from(database, table)} —
     * только второе; расхождение и было уязвимостью.
     *
     * @param qualifier имя схемы или базы данных, может быть {@code null}
     * @param raw       имя таблицы или колонки
     * @return валидированный идентификатор
     * @throws QueryException если любое из имён недопустимо
     */
    public static @NotNull Identifier of(@Nullable String qualifier, @Nullable String raw) {

        Identifier self = new Identifier(validate(raw, "name"), null);

        return qualifier == null
                ? self
                : new Identifier(validate(raw, "name"), new Identifier(validate(qualifier, "qualifier"), null));

    }

    /**
     * Проверяет имя и возвращает его же.
     *
     * @param raw  проверяемое имя
     * @param role роль имени в сообщении об ошибке ("name" или "qualifier")
     * @return имя без изменений
     * @throws QueryException если имя недопустимо
     */
    private static @NotNull String validate(@Nullable String raw, @NotNull String role) {

        if (raw == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT,
                    "Identifier " + role + " must not be null");
        }

        if (raw.isBlank()) {
            throw QueryException.of(ErrorCode.INVALID_IDENTIFIER,
                    "Identifier " + role + " must not be blank",
                    Map.of("role", role, "value", raw));
        }

        if (raw.length() > MAX_LENGTH) {
            throw QueryException.of(ErrorCode.INVALID_IDENTIFIER,
                    "Identifier " + role + " is longer than " + MAX_LENGTH + " characters",
                    Map.of("role", role, "length", raw.length()));
        }

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            // Управляющие символы запрещены независимо от диалекта: NUL обрывает
            // строку в нативном слое, а \n и \r ломают однострочное логирование
            // запроса, из-за чего инцидент невозможно разобрать по логу.
            if (Character.isISOControl(c)) {
                throw QueryException.of(ErrorCode.INVALID_IDENTIFIER,
                        "Identifier " + role + " contains a control character at index " + i,
                        Map.of("role", role, "index", i, "codePoint", (int) c));
            }
        }

        return raw;

    }

    public @NotNull String getName() {
        return name;
    }

    public @Nullable Identifier getQualifier() {
        return qualifier;
    }

    /**
     * Рендерит идентификатор в кавычках диалекта вместе с квалификатором.
     *
     * @param dialect диалект, определяющий символ кавычек и способ экранирования
     * @return {@code "schema"."name"} либо {@code "name"}, если квалификатора нет
     */
    public @NotNull String render(@NotNull Dialect dialect) {
        String rendered = dialect.quote(name);
        return qualifier == null ? rendered : qualifier.render(dialect) + "." + rendered;
    }

    @Override
    public boolean equals(Object o) {

        if (this == o) {
            return true;
        }

        if (!(o instanceof Identifier other)) {
            return false;
        }

        return name.equals(other.name)
                && (qualifier == null ? other.qualifier == null : qualifier.equals(other.qualifier));

    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + (qualifier == null ? 0 : qualifier.hashCode());
    }

    /**
     * Строковое представление — неквалифицированное имя без кавычек.
     *
     * <p>Используется в сообщениях об ошибках и в логах: кавычки там только
     * мешают, а диалект в момент форматирования сообщения может быть неизвестен.
     */
    @Override
    public String toString() {
        return qualifier == null ? name : qualifier.name + "." + name;
    }

}
