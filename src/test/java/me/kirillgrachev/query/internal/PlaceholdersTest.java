package me.kirillgrachev.query.internal;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Тесты {@link Placeholders}: подсчёт {@code ?} с учётом лексической структуры.
 *
 * <p>Исходная библиотека считала символы посимвольно по всей строке, из-за чего
 * валидный SQL с вопросительным знаком внутри литерала отвергался, а в обратную
 * сторону такой знак «съедал» чужой параметр и сдвигал все последующие значения.
 */
class PlaceholdersTest {

    @Test
    void countsBarePlaceholders() {

        assertEquals(0, Placeholders.count("SELECT 1"));
        assertEquals(1, Placeholders.count("a = ?"));
        assertEquals(3, Placeholders.count("a = ? AND b = ? OR c = ?"));

    }

    @Test
    void ignoresPlaceholderInsideSingleQuotedLiteral() {
        assertEquals(0, Placeholders.count("name = 'a?b'"));
        assertEquals(1, Placeholders.count("name = 'a?b' AND id = ?"));
    }

    @Test
    void understandsDoubledQuoteAsEscape() {
        // 'it''s ?' — литерал с апострофом, вопросительный знак внутри него.
        assertEquals(0, Placeholders.count("name = 'it''s ?'"));
        assertEquals(1, Placeholders.count("name = 'it''s ?' AND id = ?"));
    }

    @Test
    void ignoresPlaceholderInsideQuotedIdentifier() {

        assertEquals(0, Placeholders.count("SELECT \"weird?col\" FROM t"));
        assertEquals(0, Placeholders.count("SELECT `weird?col` FROM t"));
        assertEquals(0, Placeholders.count("SELECT [weird?col] FROM t"));
        assertEquals(1, Placeholders.count("SELECT \"weird?col\" FROM t WHERE id = ?"));

    }

    @Test
    void ignoresPlaceholdersInLineComment() {
        assertEquals(0, Placeholders.count("a = 1 -- why? because ?"));
        assertEquals(1, Placeholders.count("a = ? -- comment with ? inside"));
    }

    @Test
    void ignoresPlaceholdersInBlockComment() {
        assertEquals(0, Placeholders.count("a = 1 /* what about ? */"));
        assertEquals(2, Placeholders.count("a = ? /* ? */ AND b = ?"));
    }

    @Test
    void unterminatedLiteralSwallowsTheRest() {
        // Несбалансированная кавычка трактуется консервативно: остаток строки
        // считается содержимым литерала. Это даёт заниженное число плейсхолдеров
        // и понятную ошибку валидации вместо молча неверного запроса.
        assertEquals(0, Placeholders.count("name = 'unterminated ?"));
    }

    @Test
    void checkCountPassesOnMatch() {
        Placeholders.checkCount("a = ? AND b = ?", new Object[]{1, 2});
        Placeholders.checkCount("name = 'a?b'", new Object[]{});
    }

    @Test
    void checkCountReportsBothNumbers() {

        QueryException e = assertThrows(QueryException.class,
                () -> Placeholders.checkCount("a = ? AND b = ?", new Object[]{1}));

        assertEquals(ErrorCode.PLACEHOLDER_COUNT_MISMATCH, e.getCode());
        assertEquals(2, (int) e.getContextValue("expected", Integer.class));
        assertEquals(1, (int) e.getContextValue("actual", Integer.class));

    }

}
