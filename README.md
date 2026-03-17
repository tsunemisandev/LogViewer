# Log Viewer

Swing ベースのスマートログビューアです。ログファイルをストリームとして読み込み、プラグインチェーンでデータを処理・可視化します。

## 必要環境

- Java 17 以上
- Gradle 8.x 以上（または同梱の `./gradlew` を使用）

---

## プロジェクト構成

```
LogViewer/
├── app/                      # メインアプリケーション（Plugin API + UI + RawStreamPlugin）
├── builtin-plugins/          # 組み込みプラグイン（別プロジェクト）
├── sample-plugin/            # 外部プラグインサンプル
├── plugins/                  # ビルド時に生成される JAR 配置ディレクトリ
├── recordings/               # RecorderPlugin が保存するファイル（実行時生成）
└── sample.log                # サンプルログファイル
```

---

## ビルドと起動

```bash
# アプリ起動（ビルド + プラグイン配置 + 実行を一括実行）
./gradlew :app:run

# プラグイン JAR のビルドと配置のみ
./gradlew :app:copyPlugins

# 全プロジェクトのビルド
./gradlew build
```

> Windows の場合は `./gradlew` の代わりに `gradlew.bat` を使用してください。

---

## 機能概要

### ログソース

| 操作 | 説明 |
|---|---|
| File > Open File... | ファイルを一度だけ読み込む |
| File > Open File (Live Tail)... | ファイルの末尾を 200ms 間隔でポーリング（ライブ監視） |

複数のソースを同時に開けます。左のソースリストからアクティブなソースを切り替えられます。

### タブ

各ソースには以下のタブが表示されます。

| タブ | プラグイン | 説明 |
|---|---|---|
| Raw | RawStreamPlugin | 全行をテキストで表示 |
| Grid | GridPlugin | レコードを表形式で表示（ソート可能） |
| HTML | HtmlPlugin | レベル別カラーコードで HTML 表示 |
| Stats | StatsPlugin | レベル別カウントと棒グラフ |
| Recorder | RecorderPlugin | ストリームをファイルに録画 |
| Check Results | CheckResultTablePlugin | 抽出されたフィールドをテーブル表示（外部プラグイン） |

---

## 対応ログフォーマット

タイムスタンプで始まる行が新しいレコードの開始を示します。それ以降の行（スタックトレースや追加データ）は同一レコードの継続行として扱われます。

```
2026-03-16 14:22:01.123 情報  [main] アプリケーション起動
2026-03-16 14:22:01.456 エラー [worker-1] 接続失敗
    id/name:10/john
    チェック呼び出し結果：NG
2026-03-16 14:22:02.000 情報  [main] シャットダウン完了
```

**対応レベル表記:**

| ログ内の表記 | 正規化後 |
|---|---|
| エラー | ERROR |
| 警告 | WARN |
| 情報 | INFO |
| デバッグ | DEBUG |

**カスタムフィールド抽出:**

継続行のデータ構造を自動的に `LogRecord.fields` に展開します。

| パターン | 例 | 展開結果 |
|---|---|---|
| `key1/key2:val1/val2` | `id/name:10/john` | `id=10`, `name=john` |
| `key：value` / `key:value` | `チェック呼び出し結果：OK` | `チェック呼び出し結果=OK` |

---

## Recorder の使い方

ライブ監視中に特定の時間帯だけを切り出して分析するワークフローです。

```
1. ログファイルをライブ Tail で開く
        ↓
2. Recorder タブ → [Start] で録画開始
        ↓
3. 分析したい操作を実施
        ↓
4. [Stop] で録画終了
        ↓
5. [→ ソースとして開く] で録画ファイルを新しいソースとして追加
        ↓
6. Check Results タブでその時間帯のデータを確認
```

録画ファイルは `./recordings/log-<タイムスタンプ>.log` に保存されます。

---

## プラグイン開発

外部プラグインは `./plugins/` ディレクトリに JAR を配置するだけで読み込まれます。

### Plugin インターフェース

```java
public interface Plugin {
    String id();
    String tabLabel();                          // null の場合はタブを表示しない（headless）
    void onEvent(LogEvent event);               // 生行ごとに呼ばれる
    default void onRecord(LogRecord record) {}  // 組み立て済みレコードごとに呼ばれる
    void onEndOfStream();
    JComponent getComponent();                  // null の場合はタブを表示しない

    default void init(Map<String, Object> context) {}  // 起動時の依存注入
    default int priority() { return 0; }               // 小さい値ほど先に実行
}
```

### LogRecord の構造

```java
record LogRecord(
    List<String>        rawLines,  // 全原文行
    Map<String, String> fields     // 抽出されたフィールド（スキーマなし）
)
```

主なフィールドキー:

| キー | 内容 |
|---|---|
| `timestamp` | タイムスタンプ文字列 |
| `level` | 原文のレベル（例: `エラー`） |
| `level.normalized` | 正規化後のレベル（`ERROR` / `WARN` / `INFO` / `DEBUG`） |
| `thread` | スレッド名 |
| `message` | メッセージ |
| `_peek` | `"true"` の場合はライブ Tail のプレビュー行（確定前） |

### JAR の作成手順

1. `app` プロジェクトを `compileOnly` 依存として追加
2. `Plugin` インターフェースを実装
3. `META-INF/services/com.logviewer.plugin.Plugin` に実装クラス名を記載
4. JAR を `./plugins/` に配置

```
myplugin.jar
├── com/example/MyPlugin.class
└── META-INF/services/
    └── com.logviewer.plugin.Plugin   ← "com.example.MyPlugin"
```

サンプル実装は [`sample-plugin/`](sample-plugin/) を参照してください。

### `init()` による依存注入

`RecorderPlugin` のように上位の機能を必要とする場合は `init()` を実装してください。

```java
@Override
public void init(Map<String, Object> context) {
    // 新しいソースタブを開くコールバック
    Consumer<LogSource> addSource = (Consumer<LogSource>) context.get("addSource");
}
```

### priority() によるプラグイン実行順

フィールドを enrichment するプラグインはレンダープラグインより先に実行する必要があります。

| 値 | 用途 |
|---|---|
| `-20` | FieldExtractorPlugin（フィールド抽出） |
| `-10` | LevelNormalizerPlugin（レベル正規化） |
| `0` (デフォルト) | レンダープラグイン |

---

## アーキテクチャ

```
FileLogSource
    │
    ▼ (バックグラウンドスレッド)
FileLineReader  ─── null を返す（idle）
    │                      │
    ▼                      ▼
LineAssembler         emitPeek()  ← ライブ Tail でのプレビュー
    │
    ▼ LogRecord
LogPipeline
    ├── FieldExtractorPlugin   (priority -20, headless)
    ├── LevelNormalizerPlugin  (priority -10, headless)
    ├── RawStreamPlugin        (priority  0)
    ├── GridPlugin             (priority  0)
    ├── HtmlPlugin             (priority  0)
    ├── StatsPlugin            (priority  0)
    ├── RecorderPlugin         (priority  0)
    └── [外部プラグイン...]
             │
             ▼ SwingUtilities.invokeLater
            EDT（UI 更新）
```
