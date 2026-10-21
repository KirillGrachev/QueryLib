package me.kirillgrachev.query.exception;

/**
 * Реестр всех кодов ошибок библиотеки.
 *
 * <p>Каждый код знает своё сообщение по умолчанию и категорию, поэтому исключение
 * создаётся в одну строку: {@code throw QueryException.of(ErrorCode.INVALID_IDENTIFIER);}
 *
 * <p>Это единственное место, где описано «что может пойти не так»: новый сценарий
 * ошибки начинается отсюда, а не с места броска. Иначе сообщения расползаются по
 * коду, и по тексту ошибки невозможно понять, какое место её породило.
 *
 * <p>Сообщения по умолчанию на английском: они попадают в логи и в трекеры, где
 * их ищут поиском. Javadoc и комментарии — на русском.
 */
public enum ErrorCode {

    /** Имя таблицы, колонки или схемы не прошло валидацию. */
    INVALID_IDENTIFIER("Identifier is not valid", ErrorCategory.VALIDATION),

    /** Число плейсхолдеров {@code ?} не совпало с числом переданных параметров. */
    PLACEHOLDER_COUNT_MISMATCH("Placeholder count does not match parameter count", ErrorCategory.VALIDATION),

    /** Передан null там, где он не допускается контрактом метода. */
    NULL_ARGUMENT("Argument must not be null", ErrorCategory.VALIDATION),

    /** Значение аргумента вне допустимого диапазона (отрицательный limit, пустой список). */
    INVALID_ARGUMENT_VALUE("Argument value is out of the allowed range", ErrorCategory.VALIDATION),

    /** Диалект не поддерживает запрошенную конструкцию. */
    UNSUPPORTED_DIALECT_FEATURE("Requested feature is not supported by the dialect", ErrorCategory.VALIDATION),

    /** Запрошен SQL, но обязательная часть запроса не задана. */
    INCOMPLETE_STATEMENT("Statement is incomplete and cannot be rendered to SQL", ErrorCategory.SQL_GENERATION),

    /** Клаузы, которые нельзя сочетать, заданы одновременно. */
    CONFLICTING_CLAUSES("Statement contains clauses that cannot be combined", ErrorCategory.SQL_GENERATION),

    /** Колонка или значение добавлены в билдер дважды. */
    DUPLICATE_ELEMENT("The same element was added to the statement twice", ErrorCategory.SQL_GENERATION),

    /** Запрошено изменение всех строк (UPDATE или DELETE) без явного подтверждения намерения. */
    UNCONDITIONAL_MODIFICATION("UPDATE or DELETE without WHERE requires an explicit confirmation call", ErrorCategory.SQL_GENERATION),

    /** Драйвер вернул SQLException. */
    SQL_EXECUTION_FAILED("SQL execution failed", ErrorCategory.EXECUTION),

    /** Драйвер не смог привязать параметр указанного типа. */
    PARAMETER_BINDING_FAILED("Query parameter could not be bound", ErrorCategory.EXECUTION),

    /** Операция прервана внешним кодом (таймаут, отмена, прерывание потока). */
    EXECUTION_INTERRUPTED("SQL execution was interrupted", ErrorCategory.EXECUTION),

    /** Транзакция не могла быть ни зафиксирована, ни откатана штатно. */
    TRANSACTION_FAILED("Transaction could not be completed", ErrorCategory.EXECUTION),

    /** Statement или ResultSet не удалось закрыть; исходная ошибка важнее и сохранена как cause. */
    RESOURCE_CLOSE_FAILED("JDBC resource could not be closed", ErrorCategory.RESOURCE),

    /** Соединение недоступно: DataSource вернул null или не открытое соединение. */
    CONNECTION_UNAVAILABLE("Database connection is not available", ErrorCategory.RESOURCE);

    /** Сообщение по умолчанию (английский: уходит в логи и метрики сервиса). */
    private final String defaultMessage;

    /** Категория ошибки для быстрой маршрутизации. */
    private final ErrorCategory category;

    ErrorCode(String defaultMessage, ErrorCategory category) {

        this.defaultMessage = defaultMessage;
        this.category = category;

    }

    public String getDefaultMessage() {

        return defaultMessage;

    }

    public ErrorCategory getCategory() {

        return category;

    }

}
