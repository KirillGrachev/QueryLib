# Примеры использования

API версии 2.x. Запросы строятся через `Query`, исполняются через `Sql`.
Запрос — это данные (текст + параметры), исполнение — отдельный объект, который
держит источник соединений и диалект.

```java
import me.kirillgrachev.query.Query;
import me.kirillgrachev.query.exec.Sql;
import me.kirillgrachev.query.sql.Conditions;
import me.kirillgrachev.query.sql.Dialect;
import me.kirillgrachev.query.sql.Order;
```

## Подготовка

```java
Sql sql = Sql.of(dataSource, Dialect.POSTGRES);
```

`Sql` неизменяем и потокобезопасен: создаётся один раз на приложение.
Диалект обязателен и не угадывается — от него зависят кавычки идентификаторов,
синтаксис пагинации и форма upsert.

Доступные диалекты: `MYSQL`, `POSTGRES`, `H2`, `SQLITE`, `SQL_SERVER`, `ORACLE`,
`ANSI`, `NONE`.

## Чтение

```java
record User(long id, String name, String email) {}

List<User> users = sql.queryAll(
        Query.select("id", "name", "email").from("users")
                .where("active", true)
                .orderBy("name", Order.Direction.DESC)
                .limit(50),
        rs -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")));

Optional<User> one = sql.queryOne(
        Query.select("id", "name", "email").from("users").where("id", 42L),
        rs -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")));
```

`queryOne` возвращает пустой `Optional`, если строк нет **или** маппер вернул
`null`. Отличить эти случаи по одному `Optional` нельзя — если нужно, используйте
`queryAll` и проверяйте размер.

## Запись

```java
int inserted = sql.update(
        Query.insertInto("users")
                .value("name", "alice")
                .valueNullable("email", null)
                .value("active", true));

int updated = sql.update(
        Query.update("users")
                .value("name", "alice")
                .valueExpr("login_count", "login_count + ?", 1)
                .where("id", 42L));

Optional<Long> newId = sql.insertReturningKey(
        Query.insertInto("users").value("name", "bob"),
        rs -> rs.getLong(1));

int deleted = sql.update(Query.deleteFrom("users").where("id", 42L));
```

`UPDATE` и `DELETE` без `WHERE` не генерируются: нужно явно подтвердить намерение.

```java
sql.update(Query.deleteFrom("sessions").allRows());
```

Без `allRows()` вызов бросает `QueryException` с кодом
`UNCONDITIONAL_MODIFICATION`. Защита стоит там, где цена ошибки максимальна:
восстановление удалённых строк требует бэкапа, а исключение на этапе построения
запроса не стоит ничего.

## Upsert

Форма зависит от диалекта и выбирается автоматически:

| Диалект | Генерируемый SQL |
|---|---|
| MySQL | `... ON DUPLICATE KEY UPDATE "col" = VALUES("col")` |
| PostgreSQL, SQLite | `... ON CONFLICT (keys) DO UPDATE SET "col" = EXCLUDED."col"` |
| H2 | `MERGE INTO t (cols) KEY (keys) VALUES (...)` |

```java
sql.update(Query.insertInto("counters")
        .value("key", "hits")
        .value("value", 1)
        .onDuplicateKeyUpdateExcept("key"));   // "key" не обновляется
```

`onDuplicateKeyUpdateExcept()` без аргументов — ошибка: пустой список ключей
означал бы «обновлять всё», включая первичный ключ. Для этого есть явный
`onDuplicateKeyUpdateAll()`.

## Условия

Простые сравнения — методами запроса, составная логика — фабриками `Conditions`.

```java
Query.select("id").from("users")
        .where("active", true)                 // AND
        .whereNull("deleted_at")               // IS NULL
        .whereIn("role", List.of("admin", "owner"))
        .where(Conditions.anyOf(               // OR в скобках
                Conditions.gt("age", 18),
                Conditions.eq("verified", true)));
```

`allOf` и `anyOf` всегда рендерятся в скобках, поэтому приоритет определяется
структурой дерева, а не памятью разработчика: `a AND b OR c` в плоском списке
условий читается одним образом, а выполняется другим.

Полный набор фабрик: `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `like`, `isNull`,
`isNotNull`, `in`, `notIn`, `not`, `allOf`, `anyOf`, `raw`.

Для случаев, не покрытых фабриками, есть `raw` — и у запросов, и у условий:

```java
Query.select("id").from("users")
        .whereExpr("created_at > NOW() - INTERVAL ? DAY", 7);

Query.raw("WITH recent AS (SELECT ...) SELECT * FROM recent WHERE x = ?", 1);
```

Число плейсхолдеров проверяется с учётом строковых литералов и комментариев,
поэтому `whereExpr("name = 'a?b'")` не потребует параметра.

## Транзакции

```java
long orderId = sql.inTransaction(tx -> {
    long id = tx.insertReturningKey(
            Query.insertInto("orders").value("user_id", 42L),
            rs -> rs.getLong(1)).orElseThrow();

    tx.update(Query.insertInto("order_items")
            .value("order_id", id).value("sku", "A-1").value("qty", 2));

    tx.update(Query.update("users")
            .valueExpr("order_count", "order_count + 1")
            .where("id", 42L));

    return id;
});
```

Одно соединение на всё тело, фиксация при успешном выходе, откат при любом
исключении, восстановление `autoCommit` в `finally` — соединение возвращается
в пул в том же состоянии, в котором было взято.

Если тело бросает `QueryException`, наружу выходит она же со своим конкретным
кодом; в `TRANSACTION_FAILED` оборачиваются только «сырые» `SQLException`.

Вариант без возвращаемого значения — отдельный метод, а не перегрузка:

```java
sql.inTransactionVoid(tx -> tx.update(Query.deleteFrom("sessions").where("expired", true)));
```

Две перегрузки с функциональными интерфейсами одной арности неоднозначны для
неявно типизированной лямбды: блок, который всегда бросает исключение, совместим
и с void-, и с value-формой, и компилятор отвечает
«reference to inTransaction is ambiguous», что ничего не говорит о причине.

## Блокирующее чтение

```java
sql.inTransaction(tx -> {
    List<Long> ids = tx.selectForUpdate(
            Query.select("id").from("jobs").where("state", "new").limit(10).forUpdate(),
            rs -> rs.getLong("id"));

    tx.update(Query.update("jobs").value("state", "taken").whereIn("id", ids));

    return ids;
});
```

`FOR UPDATE` имеет смысл только внутри транзакции: вне её блокировка снимается
сразу после чтения.

## Пакеты

```java
BatchExecutor batch = sql.batch().chunkSize(500);

for (Event e : events) {
    batch.add(Query.insertInto("events")
            .value("type", e.type())
            .value("payload", e.payload()));
}

BatchResult result = batch.execute();
```

Пакет исполняется в одной транзакции и разбивается на части по `chunkSize`
(по умолчанию 500): пакет на миллион строк, собранный в памяти целиком, упирается
в `max_allowed_packet` или в OOM. Буфер очищается всегда, в том числе при ошибке,
поэтому повторный `execute()` не отправляет строки второй раз.

Результат — не сырой `int[]`:

```java
result.statementsExecuted();   // сколько запросов отправлено
result.rowsAffected();         // сумма известных счётчиков
result.unknownCounts();        // сколько раз драйвер вернул SUCCESS_NO_INFO
result.isRowCountExact();      // можно ли сравнивать rowsAffected с ожидаемым
result.effectiveRows();        // rowsAffected + unknownCounts
```

`SUCCESS_NO_INFO` (`-2`) возвращает MySQL при `rewriteBatchedStatements=true`,
когда драйвер переписывает пакет в один запрос. Сырой массив в этом случае даёт
отрицательную сумму, и проверка «затронуто столько же строк, сколько отправили»
молча не проходит.

Пакет внутри транзакции откатывается вместе с ней:

```java
sql.inTransaction(tx -> {
    tx.batch().add(...).add(...).execute();
    return null;
});
```

## Привязка параметров

Обрабатываются без явного преобразования на стороне вызывающего кода:

| Java-тип | Привязка |
|---|---|
| `null` | `setNull(i, Types.NULL)` |
| `Optional` | распаковывается, пустой → `setNull` |
| `enum` | `setString(i, name())` — не `toString()` |
| `java.time.*` | `setObject` (JDBC 4.2), зона и точность сохраняются |
| `java.sql.Date/Time/Timestamp` | `setObject` — наносекунды не теряются |
| `java.util.Date`, `Calendar` | `setTimestamp` |
| `UUID`, `Character` | `setString` |
| `byte[]` | `setBytes` |
| остальное | `setObject` |

Ошибка привязки оборачивается в `QueryException` с кодом
`PARAMETER_BINDING_FAILED` и контекстом: индекс параметра, его Java-тип и текст
запроса. Без этого сообщение драйвера «неверный тип» не говорит, о каком из
двадцати параметров речь.

## Обработка ошибок

Все ошибки библиотеки — `QueryException` (непроверяемое) с кодом из реестра
`ErrorCode` и категорией `ErrorCategory`.

```java
try {
    sql.update(statement);
} catch (QueryException e) {
    switch (e.getCategory()) {
        case VALIDATION, SQL_GENERATION -> {
            // Дефект кода: чинить, а не повторять.
            log.error("invalid query: {}", e.getCode(), e);
        }
        case EXECUTION, RESOURCE, CONNECTION -> {
            // Может быть транзитным: допустим повтор.
        }
        default -> throw e;
    }

    String failedSql = String.valueOf(e.getContext().get("sql"));
}
```

Категория отвечает на вопрос «кто виноват», а не «что случилось»: `VALIDATION` и
`SQL_GENERATION` означают дефект вызывающей стороны, их нельзя ретраить.

## Диагностика без исполнения

Запрос можно отрендерить и рассмотреть, не открывая соединения:

```java
var statement = Query.select("id", "name").from("users").where("active", true);

statement.getSql(Dialect.POSTGRES);
// SELECT "id", "name" FROM "users" WHERE "active" = ?
statement.getParams();
// [true]
```

Именно так написаны тесты генерации SQL: они не требуют базы и выполняются за
миллисекунды.

## Безопасность имён

Имена таблиц, колонок и схем попадают в запрос только через `Identifier`, который
валидируется в конструкторе. Экранирование выполняется удвоением символа кавычки,
поэтому имя с кавычкой легально и безопасно:

```java
Query.select("x").from("db`; DROP TABLE users; --", "t").getSql(Dialect.MYSQL);
// SELECT `x` FROM `db``; DROP TABLE users; --`.`t`
```

Весь payload остался внутри одной пары кавычек. Отвергаются только blank-имена,
управляющие символы (`\0` обрывает строку в нативном слое драйвера) и имена
длиннее 128 символов.

Значения всегда передаются плейсхолдерами и никогда не склеиваются в текст,
включая списки в `IN (...)`.
