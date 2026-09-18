# Columnar Query Engine（列式本地查询执行引擎）

面向本地数据集的列式 SQL 查询执行引擎。设计目标：**同一份查询在不同数据规模、
不同执行顺序与并发访问下，都得到一致、可解释、可复现的结果**。

- Java 21，零外部运行时依赖，仅 JUnit 5 用于测试
- 纯内存列式存储（INT / DOUBLE / BOOLEAN / STRING）
- 不可变表快照 + 原子替换实现快照隔离
- 字节级内存限额，超限明确失败，不静默截断

---

## 1. 快速开始

```java
ColumnarQueryEngine engine = new ColumnarQueryEngine();

engine.catalog().createTable("orders", new Schema(List.of(
        ColumnSchema.of("id",     DataType.INT),
        ColumnSchema.of("cid",    DataType.INT),
        ColumnSchema.of("amount", DataType.DOUBLE),
        ColumnSchema.of("memo",   DataType.STRING))));

engine.catalog().insertRows("orders", List.of(
        new Object[]{1, 10, 99.5, "a"},
        new Object[]{2, 10, null, "b"}));

QueryResult r = engine.execute("""
        SELECT cid, count(*) AS cnt, sum(amount) AS total
        FROM orders
        WHERE amount IS NOT NULL
        GROUP BY cid
        ORDER BY total DESC
        LIMIT 10
        """);

r.rows().forEach(row -> System.out.println(Arrays.toString(row)));
```

### 自定义内存限额

```java
QueryOptions options = new QueryOptions(
        8L * 1024 * 1024,                 // 8 MiB 工作内存上限（字节）
        QueryOptions.MemoryPolicy.FAIL);  // 超限明确失败
QueryResult r = engine.execute(sql, options);
```

---

## 2. 支持的 SQL 语法

```
SELECT [DISTINCT] projection [, ...]
FROM table [[AS] alias]
  [ { INNER | LEFT [OUTER] | RIGHT [OUTER] | FULL [OUTER] | CROSS } ]
    JOIN table [[AS] alias] ON <equi-predicate> [AND ...]
[WHERE <predicate>]
[GROUP BY <expr> [, ...]]
[HAVING <predicate>]
[ORDER BY <expr> [ASC | DESC] [NULLS FIRST | NULLS LAST] [, ...]]
[LIMIT n] [OFFSET n]
```

- 投影：列引用（`alias.column` 或 `column`）、表达式、`*`、表达式别名
- 比较：`=  <>  !=  <  >  <=  >=`
- 算术：`+ - * /`（`/` 对整数为整数除法且除零报错；DOUBLE 除零遵循 IEEE 754）
- 字符串连接：`||`（任一操作数为 NULL 结果为 NULL）
- 逻辑：`AND OR NOT`，**三值逻辑**（TRUE/FALSE/UNKNOWN）
- 空值判断：`IS NULL` / `IS NOT NULL`
- 聚合：`COUNT(*)`、`COUNT(expr)`、`SUM / AVG / MIN / MAX`，支持 `DISTINCT`
- 连接：内连接、左/右/全外连接、笛卡尔积；ON 至少包含一个等值条件，
  可附加任意剩余谓词
- 字面量：整数、小数、单引号字符串（`''` 转义）、`TRUE/FALSE/NULL`
- 注释：`-- 行注释` 与 `/* 块注释 */`

---

## 3. 查询语义（确定性保证）

### 3.1 空值（NULL）

| 场景 | 语义 |
|---|---|
| `NULL = NULL`、`NULL <> NULL` | 结果为 UNKNOWN，WHERE/HAVING 中不匹配 |
| `NULL IS NULL` | TRUE |
| `x AND NULL` / `x OR NULL` | 标准三值逻辑（如 `FALSE AND NULL → UNKNOWN`，`TRUE OR NULL → TRUE`） |
| 算术/比较/`||` | 任一输入为 NULL，结果为 NULL |
| WHERE 过滤 | 仅严格为 TRUE 的行保留，UNKNOWN 与 FALSE 一同排除 |

### 3.2 连接（JOIN）

- **空键永不匹配**：任一侧连接键为 NULL 的行不会产生内连接匹配；
  LEFT/RIGHT/FULL 外连接按标准规则为未匹配侧补 NULL。
- 重复键产生标准的多重集匹配（左表一行匹配右表 N 行则输出 N 行）。
- INT 与 DOUBLE 数值键可跨类型等值连接；STRING 与数值之间不可比较。

### 3.3 分组与聚合

- **GROUP BY 的空键自成一组**（注意：与连接语义不同，这是 SQL 标准）。
- 分组的输出顺序 = 各组首次出现顺序；因此分组结果在任何数据规模下顺序稳定。
- `COUNT(*)` 统计组内所有行（含键为空的行）；`COUNT(expr)`/`SUM`/`AVG`/`MIN`/`MAX`
  忽略参数为 NULL 的行。
- 无 GROUP BY 时，空输入也产生一行：`COUNT=0`，其它聚合为 NULL；
  有 GROUP BY 时空输入没有任何组。
- `SUM(INT)` 溢出以 `DATA_VIOLATION` 明确报错；`AVG` 恒为 DOUBLE。

### 3.4 排序与截断

- 排序为**稳定排序**：所有排序键相等的行保持输入相对顺序。
- NULL 顺序显式声明：默认 `ASC → NULLS LAST`、`DESC → NULLS FIRST`，
  可用 `NULLS FIRST/LAST` 覆盖。
- 非 DISTINCT 查询允许按未投影列排序；DISTINCT 查询的排序键必须出现在投影中
  （SQL 标准，分析阶段强制）。
- LIMIT/OFFSET 在排序之后应用。

### 3.5 错误分类（绝不静默返回空/残缺结果）

所有错误抛出 `QueryException`，携带可区分的 `QueryException.Code` 与
（语法错误的）基于 0 的字符位置：

| Code | 触发场景 |
|---|---|
| `SYNTAX_ERROR` | 词法/语法错误、非法 LIMIT/OFFSET、缺少 ON 等 |
| `UNKNOWN_TABLE` / `UNKNOWN_COLUMN` | 表或字段不存在 |
| `AMBIGUOUS_COLUMN` | 连接后字段名有歧义且未用别名限定 |
| `TYPE_MISMATCH` | 算术/比较/逻辑操作数类型不兼容 |
| `AGGREGATE_MISUSE` | WHERE 中使用聚合、嵌套聚合、非法 `COUNT(DISTINCT *)` 等 |
| `GROUPING_ERROR` | 投影非分组列、DISTINCT 排序键未投影等 |
| `UNSUPPORTED_FEATURE` | 未知聚合函数、无等值条件的非 CROSS JOIN 等 |
| `MEMORY_LIMIT` | 超出查询工作内存上限 |
| `DATA_VIOLATION` | 插入行列数/类型/非空约束冲突、整数除零、SUM 溢出 |
| `SCHEMA_EVOLUTION_REJECTED` | 结构演进计划校验失败 |

### 3.6 快照隔离与并发

- 每张表以**不可变快照**（schema + 列向量均不可变）存在于 `Catalog` 中；
  插入与结构演进在 `ConcurrentHashMap.compute` 内**整体构建新快照后原子替换**。
- 查询在**分析时刻**通过 `Catalog.snapshotOf(...)` 在同一提交点原子抓取
  全部来源表快照，随后执行不再读取可变目录；因此执行期间的并发写入对本查询
  不可见，读到的永远是完整提交状态，多表连接绝不会混合两个版本的数据。
- 多张表的相关变更可用 `Catalog.insertRowsAtomically(List<TableInsert>)`
  作为单个事务提交：读者要么看到整批之前、要么看到整批之后的状态。
- 任何校验失败（类型、列数、结构演进）都在替换前抛异常，**旧快照保持不变**，
  不会出现半写入数据或部分应用的结构变更。

### 3.7 结构演进

通过 `Catalog.evolve(name, SchemaEvolution.of(ops))` 提交一个演进计划：

- `AddColumn(column, defaultValue)`：新列按默认值（nullable 列允许 null）
  回填全部历史行；默认值类型必须与列类型兼容。
- `RemoveColumn(name)`：删除列，其余行原样保留。
- 计划先在**模拟 schema 上整体校验**（重名、缺列、默认值类型、非空约束），
  任一失败则整批不生效。
- 既有显式列查询在新增列后结果不变；被删除列上的查询以 `UNKNOWN_COLUMN`
  明确拒绝；`SELECT *` 反映最新结构；插入必须使用当前结构，列数不符即整体失败。

---

## 4. 内存限额语义

- 限额覆盖查询**工作内存**：连接哈希表、分组哈希表、DISTINCT 集合、
  排序键与输出物化、表达式中间向量等，按字节估计。
- 策略当前为 `FAIL`：任何一次预留会使累计用量超过上限，立即抛出
  `MEMORY_LIMIT`，查询失败且**不改变任何已有数据**，随后重试可以得到确定结果。
- 同一查询在不同限额下的行为可复现：限额充足时结果与无限额完全一致；
  限额不足时失败点固定（按算子流水线顺序记账）。
- 表数据本身驻留在不可变快照中，不计入单次查询限额。
- 记账为**保守的上界估计**：中间结果在算子交接时按字节重复累计（旧帧不可变、
  可能仍被引用），因此限额可能比理论峰值更早触发 FAIL，但绝不会漏算导致无界占用。
  这保证了“限额充足则结果与无限额完全一致；限额不足则明确失败”的可复现语义。

---

## 5. 构建与验证

```bash
mvn compile     # 编译
mvn test        # 运行全部 78 个自动化测试
```

测试覆盖（`src/test/java/com/github/lechandonga/cqe/`）：

| 测试类 | 覆盖内容 |
|---|---|
| `QueryValidationTest` | 语法错误与位置、未知表/字段、歧义字段、类型不匹配、聚合/分组误用 |
| `NullAndJoinSemanticsTest` | NULL 三值逻辑、IS NULL、四类外连接、空键、重复键、空组、DISTINCT 聚合 |
| `MemoryLimitTest` | 排序/连接/去重超限失败、失败后数据完好、限额充足结果一致 |
| `ConcurrentAccessTest` | 并发读写不见半写入、单查询多表快照一致、失败更新原子性、限额失败隔离 |
| `SchemaEvolutionTest` | 加列回填、删列、整体生效/整体回滚、默认值类型规则、演进后写入 |
| `DeterminismTest` | 分组顺序跨规模稳定、重复键稳定排序、分页、DISTINCT、连接顺序不改变多重集 |
| `SmokeTest` | 端到端冒烟 |

---

## 6. 代码结构

```
com.github.lechandonga.cqe
├── ColumnarQueryEngine   门面：解析 → 校验（固定快照）→ 执行
├── QueryOptions/QueryException/QueryResult
├── type/                 DataType、ColumnSchema、Schema
├── column/               列式向量（Int/Double/Boolean/String）与构建器
├── store/                Table 不可变快照、Catalog（MVCC 替换）、SchemaEvolution
├── sql/                  Tokenizer、QueryParser（递归下降）
│   └── ast/              Expr、SelectStatement
├── analyze/              SemanticAnalyzer（名称解析、类型/聚合/分组校验）
└── exec/                 ExecutionEngine 流水线、ExprEvaluator、
                          HashJoin、HashAggregate、SortOperator、MemoryTracker、Frame
```
