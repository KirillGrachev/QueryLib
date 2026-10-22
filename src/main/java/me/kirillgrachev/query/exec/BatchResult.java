package me.kirillgrachev.query.exec;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Результат пакетного исполнения.
 *
 * <p>Существует вместо «голого» {@code int[]} именно потому, что сырой массив
 * счётчиков неоднозначен. JDBC допускает в нём три вида значений: неотрицательное
 * число затронутых строк, {@code Statement.SUCCESS_NO_INFO} ({@code -2}) и
 * {@code Statement.EXECUTE_FAILED} ({@code -3}). Драйвер, переписывающий пакет в
 * один запрос (MySQL с {@code rewriteBatchedStatements=true}), возвращает {@code -2}
 * для каждой строки, и сумма массива становится отрицательной.
 *
 * <p>Исходная библиотека возвращала массив как есть, поэтому проверка вида
 * «затронуто столько же строк, сколько отправили» молча не проходила, и её
 * обычно просто убирали. Здесь число строк и число неизвестных счётчиков — разные
 * поля, и смешать их невозможно.
 *
 * @param statementsExecuted сколько запросов было отправлено
 * @param rowsAffected       сумма известных неотрицательных счётчиков
 * @param unknownCounts      сколько элементов вернули {@code SUCCESS_NO_INFO}
 * @param rawCounts          исходные счётчики в порядке исполнения
 */
public record BatchResult(int statementsExecuted,
                          long rowsAffected,
                          int unknownCounts,
                          @NotNull List<Integer> rawCounts) {

    /**
     * Результат пустого пакета.
     *
     * @return результат с нулевыми счётчиками
     */
    public static @NotNull BatchResult empty() {
        return new BatchResult(0, 0, 0, List.of());
    }

    /**
     * Все ли отправленные строки дали известный счётчик.
     *
     * <p>Удобная проверка для кода, которому важно точное число затронутых строк:
     * если драйвер переписал пакет, точного числа нет, и сравнивать
     * {@link #rowsAffected()} с ожидаемым бессмысленно.
     *
     * @return {@code true}, если {@code SUCCESS_NO_INFO} не встречался
     */
    public boolean isRowCountExact() {
        return unknownCounts == 0;
    }

    /**
     * Число строк, которое можно предъявить как «затронуто».
     *
     * <p>Если часть счётчиков неизвестна, точное число недоступно, и метод
     * возвращает сумму известного плюс число неизвестных: каждая строка с
     * {@code SUCCESS_NO_INFO} была исполнена успешно, просто драйвер не сообщил,
     * сколько строк она затронула.
     *
     * @return нижняя оценка числа затронутых строк
     */
    public long effectiveRows() {
        return rowsAffected + unknownCounts;
    }

}
