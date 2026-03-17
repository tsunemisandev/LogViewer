# Smart Log Viewer — Specification

## Stack

- **Java 17** — no third-party libraries
- **Swing** — GUI with Windows-style LookAndFeel
- **ServiceLoader** — plugin discovery
- **URLClassLoader** — external plugin JARs

---

## Project Layout

```
logviewer/
├── logviewer.jar          ← main application
├── plugin-api.jar         ← public interfaces only (shipped separately)
├── plugins/               ← drop external plugin JARs here
│   ├── recorder-plugin.jar
│   └── html-renderer-plugin.jar
├── recordings/            ← files saved by RecorderPlugin
└── config.properties
```

---

## Configuration

File: `./config.properties` (beside the running JAR)

```properties
pipeline.plugins=lineassembler,raw,grid,html,stats,recorder
assembler.strategy=prefixdate
assembler.datePattern=yyyy-MM-dd HH:mm:ss
tail.enabled=true
tail.intervalMs=500
grid.maxRows=50000
```

Uses `java.util.Properties` only — no XML or JSON parser needed.

---

## Architecture

```
LogSource (File / stdin / socket)
    │
    ▼
StreamReader  (BufferedReader — background thread)
    │  LogEvent (raw line)
    ▼
LogPipeline
    │
    ├──► LineAssemblerPlugin      assembles lines → LogRecord     (headless)
    │         │  LogRecord (rawLines + empty fields)
    │         ▼
    ├──► FieldExtractorPlugin    extracts key/value into fields  (headless)
    │         │  LogRecord (fields populated)
    │         ▼
    ├──► LevelNormalizerPlugin   maps 日本語 → ERROR/WARN/...    (headless, optional)
    │         │
    │         ▼
    ├──► FilterPlugin            keeps / drops records           (headless)
    │
    ├──► RawStreamPlugin         renders every raw line          (Tab: Raw)
    ├──► GridPlugin              tabular view                    (Tab: Grid)
    ├──► HtmlPlugin              styled HTML                     (Tab: HTML)
    ├──► StatsPlugin             counts / error rate graph       (Tab: Stats)
    └──► RecorderPlugin          writes stream to file           (Tab: Recorder)
```

---

## Data Model

### LogEvent — raw token from stream
```java
record LogEvent(String rawLine, long offsetBytes, Instant receivedAt) {}
```

### LogRecord — loosely typed, open structure

Fixed fields are kept to the absolute minimum. Everything else goes into a
free-form `fields` map so plugins can extract and add whatever they find.

```java
record LogRecord(
    List<String>        rawLines,  // all original lines (always present)
    Map<String, String> fields     // anything extracted — no schema enforced
) {
    // Convenience accessors for well-known keys (all nullable)
    public String timestamp() { return fields.get("timestamp"); }
    public String level()     { return fields.get("level"); }
    public String message()   { return fields.get("message"); }
    public String get(String key) { return fields.get(key); }
}
```

### Well-known field keys (by convention, not enforced)

| Key | Example value | Notes |
|---|---|---|
| `timestamp` | `2026-03-16 14:22:01.123` | raw string, not parsed to Instant |
| `level` | `エラー`, `ERROR`, `警告` | Japanese or English — no normalization by default |
| `message` | `チェック呼び出し結果：OK` | first line after timestamp+level |
| `level.normalized` | `ERROR` | optional, set by a NormalizerPlugin |

Any plugin can add arbitrary keys — e.g. `id`, `name`, `result`, etc.

### Level normalization (optional plugin)

A `LevelNormalizerPlugin` maps locale-specific terms to standard values:

```properties
# config.properties
level.map.エラー=ERROR
level.map.警告=WARN
level.map.情報=INFO
level.map.デバッグ=DEBUG
```

Plugins that need normalized levels read `level.normalized` instead of `level`.

---

## Field Extraction (custom data structures)

Log lines with unusual structure like:

```
id/name:10/john
チェック呼び出し結果：OK
```

are handled by **FieldExtractorPlugin** — a headless plugin that runs after
assembly and adds extracted values into `LogRecord.fields`.

```java
public interface FieldExtractor {
    // receives a single line from the record; adds keys into target map
    void extract(String line, Map<String, String> target);
}
```

### Built-in extractors

| Extractor | Pattern matched | Fields added |
|---|---|---|
| `SlashPairExtractor` | `key1/key2:val1/val2` | `id=10`, `name=john` |
| `ColonExtractor` | `key：value` or `key:value` | `チェック呼び出し結果=OK` |
| `RegexExtractor` | configurable regex with named groups | any group name → field key |

Extractors are also loadable from external plugins (same `ServiceLoader` pattern).

---

## Plugin API  (`plugin-api.jar`)

```
com.logviewer.api
├── Plugin.java              (interface)
├── PluginUI.java            (interface)
├── LogEvent.java            (record)
├── LogRecord.java           (record)
└── LineAssemblerStrategy.java (interface)
```

### Plugin interface
```java
public interface Plugin {
    String id();
    String displayName();
    void onEvent(LogEvent event);     // raw line
    void onRecord(LogRecord record);  // assembled record
    void onEndOfStream();
    PluginUI getUI();                 // null for headless plugins
}
```

### PluginUI interface
```java
public interface PluginUI {
    JComponent getComponent();
    String     getTabLabel();
    Icon       getTabIcon();   // nullable
    void       clear();
}
```

---

## Line Assembly

`LineAssemblerPlugin` uses a strategy to decide record boundaries.

```java
public interface LineAssemblerStrategy {
    boolean isNewRecord(String line, String previousLine);
}
```

### Log format (confirmed)

A record starts at a line that begins with a timestamp. All subsequent lines
that do **not** start with a timestamp belong to the same record (e.g. stack
traces, multi-line messages).

```
2026-03-16 14:22:01.123 INFO  [main] Starting application
2026-03-16 14:22:01.456 ERROR [worker-1] Something failed
    at com.example.Foo.bar(Foo.java:42)       ← continuation (no timestamp)
    at com.example.Main.run(Main.java:10)      ← continuation (no timestamp)
2026-03-16 14:22:02.000 INFO  [main] Shutdown complete
```

### Active strategy: `PrefixDateStrategy`

```java
// new record = line starts with a timestamp pattern
private static final Pattern TIMESTAMP =
    Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

public boolean isNewRecord(String line, String previousLine) {
    return TIMESTAMP.matcher(line).find();
}
```

Configured via `config.properties`:
```properties
assembler.strategy=prefixdate
assembler.datePattern=yyyy-MM-dd HH:mm:ss
```

### Other available strategies (for future use)

| Strategy | Rule |
|---|---|
| `LevelPrefixStrategy` | new record if line starts with INFO/WARN/ERROR/DEBUG |
| `RegexStrategy` | new record if line matches a configurable regex |
| `IndentContinuationStrategy` | continuation if line starts with whitespace |
| `PassthroughStrategy` | every line = one record (fallback) |

---

## Built-in Plugins

### RawStreamPlugin
- Input: `LogEvent`
- Render: `JTextArea` — appends every raw line
- Tab label: **Raw**

### GridPlugin
- Input: `LogRecord`
- Render: `JTable` with columns: timestamp, level, thread, logger, message
- Features: column sorting, max-rows cap (`grid.maxRows`)
- Tab label: **Grid**

### HtmlPlugin
- Input: `LogRecord`
- Render: `JEditorPane` with inline HTML
- Level color coding (ERROR=red, WARN=orange, INFO=blue, DEBUG=grey)
- Tab label: **HTML**

### StatsPlugin
- Input: `LogRecord`
- Render: custom-painted panel — counts per level, errors/sec graph
- Tab label: **Stats**

### RecorderPlugin
- Input: `LogEvent` (raw lines)
- UI: Start / Stop buttons + status label + current file path
- On **Start**: opens `FileWriter` → `./recordings/log-<ISO-timestamp>.log`
- On **Stop**: flushes, closes the writer, enables **「ソースとして開く」** button
- **「ソースとして開く」**: adds the saved file to the Source List as a new `FileLogSource` — pipeline starts immediately, no manual file browsing needed
- Tab label: **Recorder**

### HtmlFileRendererPlugin
- Input: reads a log file (e.g. a recording)
- UI: file picker / dropdown + `JEditorPane`
- Uses `PrefixDateStrategy` (format confirmed — timestamp-delimited records)
- Renders assembled `LogRecord`s as styled HTML
- Tab label: **File Viewer**

### CheckResultTablePlugin  *(external plugin — `./plugins/`)*
- Input: `LogRecord` (fields populated by `FieldExtractorPlugin`)
- Filters: only records that contain a check-result field (e.g. `チェック呼び出し結果`)
- Renders: `JTable` with columns — タイムスタンプ / チェック名 / 結果 / 詳細
- `NG` rows highlighted in red via `TableCellRenderer`
- Tab label: **チェック結果**

```
┌──────────────┬──────────────┬──────────┬─────────────────┐
│ タイムスタンプ │  チェック名   │  結果    │  詳細           │
├──────────────┼──────────────┼──────────┼─────────────────┤
│  14:22:01    │  接続チェック  │  ✓ OK   │  id=10 name=john│
│  14:22:02    │  認証チェック  │  ✗ NG   │  code=401       │
│  14:22:03    │  DBチェック   │  ✓ OK   │  rows=42        │
└──────────────┴──────────────┴──────────┴─────────────────┘
```

---

## Plugin Loading

### Built-in plugins
Loaded via `ServiceLoader.load(Plugin.class)` from the app classpath.

### External plugins
```java
Path pluginDir = Path.of("plugins");   // beside the running JAR only

URL[] jarUrls = {};
if (Files.isDirectory(pluginDir)) {
    jarUrls = Files.list(pluginDir)
        .filter(p -> p.toString().endsWith(".jar"))
        .map(p -> p.toUri().toURL())
        .toArray(URL[]::new);
}

URLClassLoader pluginClassLoader =
    new URLClassLoader(jarUrls, AppMain.class.getClassLoader());

ServiceLoader<Plugin> external =
    ServiceLoader.load(Plugin.class, pluginClassLoader);
```

### External plugin JAR contract
1. Compile against `plugin-api.jar`
2. Implement `com.logviewer.api.Plugin`
3. Declare in `META-INF/services/com.logviewer.api.Plugin`
4. Drop JAR into `./plugins/`

```
myplugin.jar
├── com/example/MyPlugin.class
└── META-INF/services/
    └── com.logviewer.api.Plugin    ← "com.example.MyPlugin"
```

> Plugins run fully trusted — no `SecurityManager` in Java 17.

---

## Threading Model

| Thread | Responsibility |
|---|---|
| **EDT** (Swing) | All UI updates |
| **Reader thread** (1 per source) | Reads `InputStream`, produces `LogEvent` into a `BlockingQueue` |
| **Pipeline thread** (1 per source) | Drains queue, calls headless plugins, assembles `LogRecord`s |
| **Render plugins** | Post to EDT via `SwingUtilities.invokeLater` |

---

## Look and Feel

```java
// 起動時に設定 — Windows風のシステムLaFを使用
try {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
} catch (Exception e) {
    // fallback to default Metal LaF — no crash
}
```

- **Windows環境**: `WindowsLookAndFeel` が自動適用される
- **macOS / Linux**: システムのネイティブLaFにフォールバック（同じコードで動く）
- カスタムテーマ・独自フォント・装飾は**一切なし** — シンプルを維持
- ウィンドウ、ボタン、テーブル、タブはすべてデフォルトのシステム標準コンポーネントをそのまま使用

---

## GUI Layout

```
┌─────────────────────────────────────────────────────────────────┐
│  Menu: File | View | Plugins | Settings                         │
├────────────────┬────────────────────────────────────────────────┤
│  Sources       │  [Raw] [Grid] [HTML] [Stats] [Recorder]        │
│  (JList)       │  [チェック結果] ...                             │
│                │                                                 │
│  ● app.log     │  <active plugin renders here>                  │
│    (live tail) │                                                 │
│  ● log-14-22   │                                                 │
│    (recording) │                                                 │
│                │                                                 │
├────────────────┴────────────────────────────────────────────────┤
│  Status: lines read: 1,042 | records: 998 | tail: ON            │
└─────────────────────────────────────────────────────────────────┘
```

- **Left panel**: open log sources — click to switch active pipeline
- **Right panel**: `JTabbedPane` — one tab per render plugin
- Each source gets its own `LogPipeline` and **independent plugin instances**
- Live sources and recording files coexist simultaneously in the source list
- **Status bar**: live counters + tail indicator

---

## Use Case: 時間帯チェック結果の可視化

典型的なワークフロー:

```
1. app.log をライブ監視中
        │
        ▼
2. [Recorder] Start → 問題のある時間帯を録画 → Stop
        │
        ▼
3. [→ ソースとして開く] ボタンを押す
        │
        ▼
4. Source List に録画ファイルが追加される
   新しい LogPipeline が起動（tail なし、一気に読み切る）
        │
        ▼
5. [チェック結果] タブを選択
   その時間帯のチェック処理結果がテーブルに一覧表示される
```

ポイント:
- ライブの `app.log` パイプラインとは**完全に独立**
- 録画ファイルは `tail.enabled=false` で動作 — EOF で停止
- `CheckResultTablePlugin` は外部JARなので本体を変更せず追加可能

---

## Tail Mode

Uses `java.nio.file.WatchService` to detect file growth.

```properties
tail.enabled=true
tail.intervalMs=500
```

---

## Implementation Plan

各フェーズは**単独で起動・動作確認できる**状態で完結する。

---

### Phase 1 — ウィンドウが開く

**目標**: アプリが起動してウィンドウが表示される

- `AppMain` — `main()` エントリポイント
- `UIManager.setLookAndFeel(システムLaF)`
- `MainWindow` — `JFrame` + Source List(空) + `JTabbedPane`(空) + ステータスバー
- ハードコードのサンプルログ行を `JTextArea` に表示（配線なし）

**確認**: ウィンドウが開き、Windows風の見た目になっている

---

### Phase 2 — ファイルを読んでRawタブに表示

**目標**: ログファイルを読んで生テキストを表示する

- `LogSource` / `FileLogSource`
- `LogEvent` (record)
- `StreamReader` — バックグラウンドスレッドでファイルを読む
- `RawStreamPlugin` — `LogEvent` を受け取り `JTextArea` に追記
- Source List にファイルを1件追加してパイプラインを接続

**確認**: ファイルを指定するとRawタブに全行が流れてくる

---

### Phase 3 — レコード組み立て + Gridタブ

**目標**: 複数行をまとめてLogRecordにして構造化表示する

- `LogRecord` (record — rawLines + fields map)
- `LineAssemblerPlugin` + `PrefixDateStrategy`
- `LogPipeline` — `BlockingQueue` + パイプラインスレッド
- `GridPlugin` — `LogRecord` を `JTable` に表示（列: timestamp / level / message）

**確認**: スタックトレースが1レコードにまとまってGridに表示される

---

### Phase 4 — Tailモード（ライブ監視）

**目標**: ファイルに追記されたら自動で画面に反映される

- `WatchService` によるファイル監視
- `tail.enabled=true` 設定の読み込み
- ステータスバーに `tail: ON` 表示

**確認**: 別ターミナルでログファイルに行を追記すると画面がリアルタイムに更新される

---

### Phase 5 — フィールド抽出 + HTMLタブ

**目標**: ログ内の独自データ構造を解析してHTMLで色付き表示する

- `FieldExtractor` インターフェース
- `FieldExtractorPlugin` — `SlashPairExtractor` / `ColonExtractor`
- `LevelNormalizerPlugin` — `エラー→ERROR` などのマッピング
- `HtmlPlugin` — `JEditorPane` でレベル別カラー表示

**確認**: `id/name:10/john` が `id=10, name=john` としてフィールドに入る。HTMLタブでエラーが赤く表示される

---

### Phase 6 — 複数ソース + Recorder

**目標**: ライブ監視しながら録画し、録画ファイルを別ソースとして開ける

- Source List の複数エントリ管理（ソースごとに独立した `LogPipeline`）
- `RecorderPlugin` — Start/Stop + `FileWriter` → `./recordings/`
- **「ソースとして開く」** ボタン — Stop後に有効化、押すとSource Listに追加

**確認**:
1. ライブ監視中にRecorderでStart→Stop
2. 「ソースとして開く」を押す
3. Source Listに録画ファイルが追加され、その内容がGridに表示される
4. ライブの方は影響を受けていない

---

### Phase 7 — Stats + 外部プラグインローディング

**目標**: グラフ表示と外部JAR読み込みが動く

- `StatsPlugin` — レベル別カウント + `paintComponent` で棒グラフ
- `URLClassLoader` + `ServiceLoader` による `./plugins/*.jar` 読み込み
- 空の `./plugins/` ディレクトリでも起動が壊れないことを確認

**確認**: Statsタブにカウントが表示される。`./plugins/` にJARを置くと追加タブが出る

---

### Phase 8 — CheckResultTablePlugin（外部JARサンプル）

**目標**: 外部プラグインとして `CheckResultTablePlugin` を実装・動作確認する

- `plugin-api.jar` を独立ビルド
- `CheckResultTablePlugin` を別プロジェクトとしてビルド → `./plugins/` に配置
- チェック結果レコードをテーブル表示、NG行赤ハイライト

**確認**:
1. Phase 6の録画ファイルをソースとして開く
2. チェック結果タブにその時間帯の結果一覧が表示される
3. NGの行が赤くなっている

---

### フェーズ依存関係

```
Phase 1 (ウィンドウ)
    │
    └─► Phase 2 (Raw表示)
              │
              └─► Phase 3 (レコード組み立て + Grid)
                        │
                        ├─► Phase 4 (Tail)
                        │
                        └─► Phase 5 (フィールド抽出 + HTML)
                                  │
                                  └─► Phase 6 (複数ソース + Recorder)
                                            │
                                            └─► Phase 7 (Stats + 外部プラグイン)
                                                          │
                                                          └─► Phase 8 (CheckResultTable)
```
