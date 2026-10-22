package me.kirillgrachev.query.internal;

import lombok.experimental.UtilityClass;
import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Подсчёт плейсхолдеров {@code ?} с учётом лексической структуры SQL.
 *
 * <p>Исходная библиотека считала символы {@code ?} посимвольно по всей строке.
 * Из-за этого валидный запрос с вопросительным знаком внутри строкового литерала
 * отвергался: {@code whereExpr("name = 'a?b'")} требовал один параметр, которого
 * нет. Обратная ошибка опаснее — {@code '?'} внутри литерала «съедал» чужой
 * параметр, и все последующие значения съезжали на одну позицию.
 *
 * <p>Здесь строковые литералы и комментарии пропускаются:
 * <ul>
 *   <li>одинарные кавычки с удвоением как экранированием ({@code 'it''s'});</li>
 *   <li>двойные кавычки — в стандартном SQL это идентификатор, внутри него
 *       {@code ?} тоже не плейсхолдер;</li>
 *   <li>обратные кавычки — идентификатор MySQL;</li>
 *   <li> square brackets — идентификатор SQL Server;</li>
 *   <li>линейный комментарий {@code --} до конца строки;</li>
 *   <li>блочный комментарий {@code /* *}{@code /}.</li>
 * </ul>
 *
 * <p>Полноценный парсер SQL здесь намеренно не строится: задача — не пропустить
 * плейсхолдер внутри литерала, а не разобрать запрос. Недоопределённые случаи
 * (несбалансированные кавычки) трактуются консервативно — остаток строки
 * считается литералом, что даёт заниженное число плейсхолдеров и понятную
 * ошибку валидации вместо молча неверного запроса.
 */
@UtilityClass
public class Placeholders {

    /**
     * Считает число плейсхолдеров {@code ?} вне строковых литералов и комментариев.
     *
     * @param sql разбираемый фрагмент SQL
     * @return число плейсхолдеров
     */
    public int count(@NotNull String sql) {

        int count = 0;
        int i = 0;
        int length = sql.length();

        while (i < length) {
            char c = sql.charAt(i);

            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c, c);
                continue;
            }

            if (c == '[') {
                i = skipQuoted(sql, i, '[', ']');
                continue;
            }

            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }

            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }

            if (c == '?') {
                count++;
            }

            i++;
        }

        return count;

    }

    /**
     * Проверяет, что число плейсхолдеров совпадает с числом параметров.
     *
     * @param sql    фрагмент SQL с плейсхолдерами
     * @param params переданные параметры
     * @throws QueryException с кодом {@link ErrorCode#PLACEHOLDER_COUNT_MISMATCH} при расхождении
     */
    public void checkCount(@NotNull String sql, @NotNull Object[] params) {

        int found = count(sql);

        if (found != params.length) {
            throw QueryException.of(ErrorCode.PLACEHOLDER_COUNT_MISMATCH,
                    "Expression expects " + found + " placeholder(s) but " + params.length
                            + " parameter(s) were given",
                    Map.of("sql", sql, "expected", found, "actual", params.length));
        }

    }

    /**
     * Пропускает участок в кавычках, учитывая удвоение символа как экранирование.
     *
     * @param sql    строка
     * @param from   индекс открывающей кавычки
     * @param open   открывающий символ
     * @param close  закрывающий символ (для {@code [ ... ]} отличается от открывающего)
     * @return индекс сразу за закрывающим символом, либо длина строки, если пара не найдена
     */
    private int skipQuoted(@NotNull String sql, int from, char open, char close) {

        int i = from + 1;

        while (i < sql.length()) {
            char c = sql.charAt(i);

            if (c == close) {
                // Удвоенный символ — экранирование, а не конец участка.
                if (open == close && i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }

                return i + 1;
            }

            i++;
        }

        // Несбалансированная кавычка: остаток строки считается содержимым
        // литерала, поэтому плейсхолдеры в нём не учитываются.
        return sql.length();

    }

    private int skipLineComment(@NotNull String sql, int from) {
        int i = sql.indexOf('\n', from);
        return i < 0 ? sql.length() : i + 1;
    }

    private int skipBlockComment(@NotNull String sql, int from) {
        int i = sql.indexOf("*/", from + 2);
        return i < 0 ? sql.length() : i + 2;
    }

}
