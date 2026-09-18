# columnar-query-engine

面向本地数据集的列式查询执行引擎。同一份查询在不同数据规模、不同执行顺序与并发访问下，
都能得到一致且可解释的结果。

## 构建与验证

```bash
mvn compile     # 编译
mvn test        # 运行全部自动化测试（46 个用例）
```

## 支持的查询能力

```sql
SELECT * | col [AS alias] | AGG(col|*) [AS alias], ...
FROM table [alias]
[INNER|LEFT JOIN table [alias] ON 布尔表达式]
[WHERE 布尔表达式]
[GROUP BY col, ...]
[ORDER BY 输出列 [ASC|DESC], ...]
[LIMIT n]
```

- 聚合函数：`COUNT` / `SUM` / `AVG` / `MIN` / `MAX`，`COUNT(*)` 统计行数。
- 表达式：比较（`=` `!=` `<` `<=` `>` `>=`，支持跨列比较）、`AND` / `OR` / `NOT`、
  `IS NULL` / `IS NOT NULL`、括号、字面量（整数、小数、字符串、TRUE/FALSE、NULL）。
- 数据类型：`INT`、`LONG`、`DOUBLE`、`STRING`、`BOOLEAN`；任何列都可含 NULL。

## 查询语义

### 错误分类（不静默返回空结果）

所有错误以 `QueryException` 抛出，`category()` 可区分原因，语法错误带源码位置：

| 类别 | 含义 |
|---|---|
| `SYNTAX_ERROR` | 语法错误，附位置信息 |
| `UNKNOWN_TABLE` | 表不存在 |
| `UNKNOWN_FIELD` | 列不存在 / 列引用有歧义 / ORDER BY 引用了非输出列 |
| `TYPE_MISMATCH` | 类型不匹配（如字符串与数值比较、对非数值列求 SUM、非布尔 WHERE） |
| `MEMORY_LIMIT_EXCEEDED` | 超出内存限额 |
| `SCHEMA_INCOMPATIBLE` | 结构演进不兼容（重名列、删除不存在的列、删除最后一列等） |

### 空值（NULL）语义

- 三值逻辑：任何含 NULL 的比较结果为 UNKNOWN；`WHERE` / `JOIN ON` 只放行 TRUE。
- 连接键为 NULL 时不产生匹配：INNER JOIN 丢弃，LEFT JOIN 保留左侧行并将右侧列置 NULL。
- 分组键中的 NULL 归为同一组。
- 聚合忽略 NULL 输入；`COUNT(col)` 只计非 NULL 行，`COUNT(*)` 计所有行；
  全 NULL / 空输入时 `SUM`/`AVG`/`MIN`/`MAX` 结果为 NULL，`COUNT` 为 0。
- 排序：ASC 时 NULL 排最后，DESC 时 NULL 排最前。

### 结果顺序（可复现）

- 无 ORDER BY 时，结果顺序由扫描顺序确定（左表行序为主、右表行序为辅；
  分组结果按分组键首次出现顺序），与数据规模、执行方式无关。
- ORDER BY 使用稳定排序，等值行保持原有相对顺序。
- 跨类型数值比较使用精确比较（BigDecimal），不受 double 精度损失影响。

### 连接与分组

- `SELECT *` 在连接时按 from 表、join 表顺序展开；重名列自动命名为 `表名.列名`。
- 有聚合或 GROUP BY 时，SELECT 中的普通列必须是分组键，否则报 `TYPE_MISMATCH`。
- ORDER BY 只能引用输出列（列名或别名），保证排序键可解释。

## 内存限额

- 配置项：`new QueryEngine(store, maxMemoryBytes)`（默认 64 MiB）。
- 策略：**超限即明确失败**（`MEMORY_LIMIT_EXCEEDED`），绝不无界占用内存。
- 记账规则确定：对物化的中间行（连接结果、过滤结果、分组缓冲、输出行）按估算字节记账，
  同一查询 + 同一数据 + 同一限额必然得到同一结果；限额足够时结果与限额无关。

## 并发与一致性

- `DataStore` 采用 MVCC 风格：查询在执行开始时获取一致性 `Snapshot`，
  执行期间的数据变更对本次查询不可见，查询绝不会读到半写入状态。
- 所有变更（插入、加列、删列）先完整校验再原子替换（CAS）：
  校验失败时抛异常且原状态不变，不存在部分应用的变更。
- 批量插入中任何一行不合法，整批拒绝，已有数据保持不变。

## 结构演进

- `addColumn(table, column, defaultValue)`：既有行以默认值填充（允许 NULL 默认）。
- `dropColumn(table, column)`：移除列；删除后至少保留一列。
- 演进后既有查询语义确定：
  - 显式列名的查询不受新增列影响；
  - `SELECT *` 按当前结构展开（新列出现在末尾）；
  - 引用已删除列的查询以 `UNKNOWN_FIELD` 明确失败；
  - 演进校验失败（重名列、默认值类型不兼容、删除未知列/最后一列）时不应用任何变更。

## 测试覆盖

| 测试类 | 覆盖场景 |
|---|---|
| `ParserValidationTest` | 语法错误（含位置）、未知表/字段、类型不匹配、错误类别可区分 |
| `ExecutionSemanticsTest` | 空值三值逻辑、重复值、跨列比较、INNER/LEFT JOIN、分组聚合、排序与 LIMIT、结果可复现 |
| `MemoryLimitTest` | 超限明确失败、不同限额结果一致、失败可复现、失败后数据完好 |
| `ConcurrencyTest` | 快照稳定性、并发读写、失败变更不破坏数据、聚合结果来自同一快照 |
| `SchemaEvolutionTest` | 加列/删列、既有查询兼容、兼容性失败原子拒绝 |

## 代码结构

```
com.github.lechandonga.cqe
├── error      QueryException（错误类别 + 位置）
├── type       DataType
├── schema     ColumnSchema / TableSchema
├── storage    Column / Table / Snapshot / DataStore（MVCC）
├── parser     Lexer / Parser
├── query      查询 AST（Query / Expr / SelectItem / AggFunc）
├── exec       QueryValidator / QueryExecutor / MemoryTracker / ResultSet
└── engine     QueryEngine（解析 → 校验 → 快照上执行）
```
