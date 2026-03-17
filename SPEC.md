# Smart Log Viewer — Technical Specification

現在の実装状態を反映した仕様書です。

---

## スタック

- **Java 17**（サードパーティライブラリなし）
- **Swing** — システム LookAndFeel
- **Gradle 9.x** マルチプロジェクト構成
- **ServiceLoader** — プラグイン発見
- **URLClassLoader** — 外部プラグイン JAR 読み込み
- **java.util.Properties** — 設定ファイル

---

## プロジェクト構成

```
LogViewer/
├── app/                          # メインアプリ（Plugin API + UI + RawStreamPlugin）
│   └── src/main/java/com/logviewer/
│       ├── AppMain.java
│       ├── config/AppConfig.java
│       ├── ui/MainWindow.java
│       ├── pipeline/
│       │   ├── LogPipeline.java
│       │   ├── FileLineReader.java
│       │   ├── LineAssembler.java
│       │   ├── LineAssemblerStrategy.java
│       │   └── PrefixDateStrategy.java
│       ├── plugin/
│       │   ├── Plugin.java
│       │   ├── RawStreamPlugin.java
│       │   └── ExternalPluginLoader.java
│       ├── source/
│       │   ├── LogSource.java
│       │   └── FileLogSource.java
│       └── model/
│           ├── LogEvent.java
│           └── LogRecord.java
├── builtin-plugins/              # 組み込みプラグイン（外部 JAR として配置）
│   └── src/main/java/com/logviewer/plugin/
│       ├── FieldExtractorPlugin.java    (priority -20)
│       ├── LevelNormalizerPlugin.java   (priority -10)
│       ├── GridPlugin.java
│       ├── HtmlPlugin.java
│       ├── StatsPlugin.java
│       ├── RecorderPlugin.java
│       ├── CheckResultHtmlPlugin.java
│       ├── MethodTraceHtmlPlugin.java
│       └── extractor/
│           ├── FieldExtractor.java
│           ├── ColonExtractor.java
│           └── SlashPairExtractor.java
├── sample-plugin/                # 外部プラグインサンプル
│   └── src/main/java/com/logviewer/sample/
│       └── CheckResultTablePlugin.java
├── plugins/                      # ビルド時に生成（./gradlew copyPlugins）
│   ├── builtin-plugins.jar
│   └── check-result-table-plugin.jar
├── recordings/                   # RecorderPlugin の出力先（実行時生成）
└── sample.log
```

---

## 設定ファイル

パス: `~/.logviewer/config.properties`（`AppConfig` が自動で読み書き）

```properties
# 有効なプラグイン ID（カンマ区切り）。未設定時は全プラグインが有効
enabled.plugins=raw,html,grid,stats,recorder,...

# 最近開いたファイル（最大 10 件）
recent.count=2
recent.0.path=/abs/path/to/app.log
recent.0.tail=true
recent.0.tailOnly=false
recent.1.path=/abs/path/to/sample.log
recent.1.tail=false
recent.1.tailOnly=false
```

---

## データモデル

### LogEvent — ストリームからの生トークン

```java
record LogEvent(String rawLine, long offsetBytes, Instant receivedAt) {}
```

### LogRecord — 組み立て済みレコード

```java
record LogRecord(
    List<String>        rawLines,  // 全原文行（常に存在）
    Map<String, String> fields     // 抽出されたフィールド（スキーマなし、ミュータブル）
) {
    public String timestamp()      { return fields.get("timestamp"); }
    public String level()          { return fields.get("level"); }
    public String thread()         { return fields.get("thread"); }
    public String message()        { return fields.get("message"); }
    public String get(String key)  { return fields.get(key); }
}
```

### Well-known フィールドキー

| キー | 内容 |
|---|---|
| `timestamp` | タイムスタンプ文字列（例: `2026-03-16 14:22:01.123`） |
| `level` | 原文レベル（例: `エラー`） |
| `level.normalized` | 正規化後レベル（`ERROR` / `WARN` / `INFO` / `DEBUG`） |
| `thread` | スレッド名 |
| `message` | 1行目のメッセージ本文 |
| `_peek` | `"true"` のとき Live Tail のプレビュー行（確定前）。プラグインはカウントや挿入をスキップする |

---

## Plugin インターフェース

```java
public interface Plugin {
    String id();
    String tabLabel();                          // タブ表示名
    void onEvent(LogEvent event);               // 生行ごとに呼ばれる
    default void onRecord(LogRecord record) {}  // 組み立て済みレコードごと
    void onEndOfStream();
    JComponent getComponent();                  // null の場合はタブを持たない（headless）

    default void init(Map<String, Object> context) {}
    // context の既知キー:
    //   "addSource" → Consumer<LogSource>  新しいソースタブを開く（RecorderPlugin が使用）

    default int priority() { return 0; }
    // 小さい値ほど先に実行。headless enrichment プラグインは負の値を使う
}
```

---

## アーキテクチャ

```
FileLogSource
    │
    ▼ (reader-<name> スレッド — ソースごとに独立)
FileLineReader (NIO FileChannel + ポーリング)
    │ null → idle (tail モード時)
    ▼
LineAssembler (PrefixDateStrategy)
    │ LogRecord
    ▼
LogPipeline — 各ソースが独立インスタンスを持つ
    │
    ├── FieldExtractorPlugin   (priority -20, headless)  フィールド抽出
    ├── LevelNormalizerPlugin  (priority -10, headless)  日本語レベル正規化
    │
    ├── RawStreamPlugin        (priority  0)  生テキスト表示
    ├── GridPlugin             (priority  0)  フィルタ付きテーブル表示
    ├── HtmlPlugin             (priority  0)  カラー HTML 表示
    ├── StatsPlugin            (priority  0)  レベル別統計グラフ
    ├── MethodTraceHtmlPlugin  (priority  0)  メソッドトレース表
    ├── CheckResultHtmlPlugin  (priority  0)  チェック結果 HTML 表
    ├── RecorderPlugin         (priority  0)  ストリーム録画
    └── [外部プラグイン ...]
             │
             ▼ SwingUtilities.invokeLater
            EDT（UI 更新）
```

**複数ソース時の挙動**: ソースごとに独立したスレッドとプラグインインスタンスを持つ。
非アクティブなソースのプラグインも裏でデータを蓄積し続ける。ソースを切り替えた瞬間に溜まったデータが表示される。

---

## Live Tail

`FileLineReader` が `FileChannel` を 200ms ごとにポーリングする。

### 開始位置の選択

ファイルを Live Tail で開く際（メニューから、または最近開いたファイルから）、毎回ダイアログで選択する。

| 選択肢 | 動作 | `tailOnly` フラグ |
|---|---|---|
| 先頭から読む＋監視 | ファイル先頭から読んで EOF 以降を監視 | `false` |
| 末尾から監視のみ | `channel.position(channel.size())` で末尾にシークしてから監視 | `true` |

`tailOnly=true` はメモリ節約とノイズ削減のために大きいファイルで有効。

### Peek（プレビュー）

idle 時（読み込む行がない）に `LineAssembler.peek()` を呼び、現在バッファ中の未確定レコードを
`_peek=true` フィールド付きで全プラグインに送る。プラグインはこのレコードを前回の peek と
差し替えて表示し、確定したら通常レコードで置換する。

---

## フィールド抽出

`FieldExtractorPlugin`（headless, priority -20）が継続行を各 `FieldExtractor` に渡す。

| Extractor | マッチパターン | 例 | 追加フィールド |
|---|---|---|---|
| `ColonExtractor` | `key：value` または `key:value` | `チェック呼び出し結果：OK` | `チェック呼び出し結果=OK` |
| `SlashPairExtractor` | `k1/k2/…:v1/v2/…` | `id/name:10/john` | `id=10`, `name=john` |

`LevelNormalizerPlugin`（priority -10）は `level` フィールドを見て `level.normalized` を追加する。

| 原文 | 正規化後 |
|---|---|
| エラー | ERROR |
| 警告 | WARN |
| 情報 | INFO |
| デバッグ | DEBUG |

---

## 組み込みプラグイン詳細

### RawStreamPlugin
- 入力: `LogEvent`
- 表示: `JTextArea` — 生行を追記
- メモリ管理: 2MB 超過で先頭 1MB を削除（行境界で切る）
- タブ: **Raw**

### GridPlugin
- 入力: `LogRecord`
- 表示: `JTable`（列: Timestamp / Level / Thread / Message / 内容 / Lines）
- 機能: レベル別行カラー / キーワード＋レベルフィルタ / 列ソート
- メモリ管理: 100,000 行超過で先頭行をローリング削除
- `_level`（非表示列）で `TableRowSorter` + `LevelColorRenderer` が色付け
- タブ: **Grid**

### HtmlPlugin
- 入力: `LogRecord`
- 表示: `JEditorPane`（HTMLDocument）— レベル別背景色
- メモリ管理: 5,000 件上限。超過時に警告バナーを挿入して以降をスキップ
- スクロール保持: `DefaultCaret.NEVER_UPDATE` + 挿入前後で位置を保存・復元
- タブ: **HTML**

### StatsPlugin
- 入力: `LogRecord`（`_peek=true` はスキップ）
- 表示: カスタム描画パネル — レベル別カウントと棒グラフ
- タブ: **Stats**

### MethodTraceHtmlPlugin
- 入力: `LogRecord`
- 対象: message に「メソッド呼び出し開始」/ 「メソッド呼び出し終了」を含むレコード
- ペアリングキー: `thread + ":" + method`
- 終了ログが来た時点でテーブル行を挿入（開始のみの場合は `onEndOfStream()` 時に「未完了」として挿入）
- 戻り値: `戻り値` フィールド（シンプル値）または `戻り値.fieldName` プレフィックス（オブジェクト）
- 戻り値カラー: SUCCESS/OK/ALLOW=緑、ERROR/NG/FAIL/DENY=赤、未完了=黄
- タブ: **メソッドトレース**

### CheckResultHtmlPlugin
- 入力: `LogRecord`（`チェック呼び出し結果` フィールドを持つもののみ）
- 表示: HTML テーブル — 日時 / チェック ID / 呼び出し結果 / 引き渡しパラメータ
- 色: OK=ライトグリーン、NG=レッド
- タブ: **チェック結果**

### RecorderPlugin
- 入力: `LogEvent`（生行）
- UI: Start / Stop / 「ソースとして開く」ボタン
- 録画先: `./recordings/log-<ISO-timestamp>.log`
- `init(context)` で `addSource` コールバックを受け取る（ServiceLoader 要件でコンストラクタ引数不可）
- タブ: **Recorder**

### CheckResultTablePlugin（外部 JAR）
- 入力: `LogRecord`（全フィールドを表示）
- 表示: `JTable`（列: Timestamp / Field / Value）
- タブ: **Check Results**

---

## プラグイン選択と永続化

起動時に `ExternalPluginLoader.loadPlugins()` を呼んでメタデータ（id / tabLabel / priority）を取得し、
メニューバーの **Plugins** メニューにチェックボックスとして表示する。

- チェックを外したプラグインは次にファイルを開いた時から読み込まれない
- 選択状態は `~/.logviewer/config.properties` の `enabled.plugins` に保存
- 初回起動時（未設定）はすべてのプラグインが有効

---

## 外部プラグイン開発

1. `app` プロジェクトを `compileOnly` 依存として追加
2. `com.logviewer.plugin.Plugin` インターフェースを実装
3. `META-INF/services/com.logviewer.plugin.Plugin` に実装クラス名を記載
4. JAR を `./plugins/` に配置 → 次回起動時に Plugins メニューに自動追加

```
myplugin.jar
├── com/example/MyPlugin.class
└── META-INF/services/
    └── com.logviewer.plugin.Plugin   ← "com.example.MyPlugin"
```

すべての JAR は単一の `URLClassLoader` で読み込まれるため、同一 `plugins/` 内の JAR 同士はクラスを参照できる。

---

## スレッドモデル

| スレッド | 役割 |
|---|---|
| **EDT** (Swing) | 全 UI 更新 |
| **reader-\<name\>** (ソースごとに 1 本) | `FileLineReader` でポーリング → `LogPipeline` でレコード組み立て → プラグイン呼び出し |

プラグインの UI 更新はすべて `SwingUtilities.invokeLater` 経由で EDT に委譲する。
複数ソースが開いていても各スレッドは独立して動作する。

---

## GUI レイアウト

```
┌─────────────────────────────────────────────────────────────────────┐
│  File | Plugins                                                      │
├────────────────┬────────────────────────────────────────────────────┤
│  Sources       │  [Raw] [Grid] [HTML] [Stats] [メソッドトレース]     │
│  (JList)       │  [チェック結果] [Recorder] [Check Results] ...      │
│                │                                                      │
│  ● app.log     │  <アクティブプラグインの描画エリア>                  │
│    [tail]      │                                                      │
│  ● recording   │                                                      │
│                │                                                      │
├────────────────┴────────────────────────────────────────────────────┤
│  Status: app.log | lines: 1,042 | records: 998 | tail: ON           │
└─────────────────────────────────────────────────────────────────────┘
```

- **左パネル**: ソースリスト（クリックでタブ切り替え）
- **右パネル**: `JTabbedPane` — 有効なプラグインのタブを表示
- ソースごとに独立した `LogPipeline` + プラグインインスタンス
- ステータスバー: 現在選択中のソースの行数・レコード数・tail 状態

---

## メモリ管理方針

| プラグイン | 上限 | 超過時の挙動 |
|---|---|---|
| RawStreamPlugin | 2 MB | 先頭 1 MB を削除（行境界） |
| GridPlugin | 100,000 行 | 先頭行をローリング削除 |
| HtmlPlugin | 5,000 件 | 以降の挿入を停止し警告バナー表示 |
| MethodTraceHtmlPlugin | なし（確定行のみ蓄積） | — |
| CheckResultHtmlPlugin | なし | — |

`HTMLDocument` への DOM 削除は破損リスクがあるため、HtmlPlugin は削除ではなく上限停止方式を採用。
