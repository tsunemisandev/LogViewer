# Log Viewer

Swing ベースのスマートログビューアです。ログファイルをストリームとして読み込み、プラグインチェーンでデータを処理・可視化します。

## 必要環境

- Java 17 以上
- Gradle 9.x 以上（または同梱の `./gradlew` を使用）

---

## プロジェクト構成

```
LogViewer/
├── app/                      # メインアプリケーション（Plugin API + UI + RawStreamPlugin）
├── builtin-plugins/          # 組み込みプラグイン（別サブプロジェクト）
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
| File > 最近開いたファイル | 過去に開いたファイルを最大 10 件履歴から再オープン |

**ライブ監視の開始位置**

Live Tail でファイルを開く際、またはファイル履歴から tail モードのファイルを選ぶ際に、毎回開始位置を選択できます。

| 選択肢 | 説明 |
|---|---|
| 先頭から読む＋監視 | ファイルを最初から読み込んだあと末尾を監視 |
| 末尾から監視のみ | 既存の内容をスキップし、新着ログのみ表示（大きいファイルに最適） |

複数のソースを同時に開けます。左のソースリストからアクティブなソースを切り替えられます。

---

### プラグイン選択

メニューバーの **Plugins** からタブごとに表示するプラグインを ON / OFF できます。
選択状態は `~/.logviewer/config.properties` に保存され、次回起動時に引き継がれます。

---

### タブ

各ソースには以下のタブが表示されます（Plugins メニューで個別に有効/無効化可能）。

| タブ | プラグイン | 説明 |
|---|---|---|
| Raw | RawStreamPlugin | 全行をテキストで表示（ローリングバッファ 2MB） |
| Grid | GridPlugin | レコードを表形式で表示。レベル別カラー・キーワード＋レベルフィルタ・ソート対応（最大 100,000 行） |
| HTML | HtmlPlugin | レベル別カラーコードで HTML 表示（最大 5,000 件） |
| Stats | StatsPlugin | レベル別カウントと棒グラフ |
| メソッドトレース | MethodTraceHtmlPlugin | メソッド呼び出し開始／終了を対応付けて表形式表示 |
| チェック結果 | CheckResultHtmlPlugin | チェック処理の OK / NG を色付きテーブルで表示 |
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
1. ログファイルをライブ Tail で開く（末尾から監視のみ 推奨）
        ↓
2. Recorder タブ → [Start] で録画開始
        ↓
3. 分析したい操作を実施
        ↓
4. [Stop] で録画終了
        ↓
5. [→ ソースとして開く] で録画ファイルを新しいソースとして追加
        ↓
6. Grid / メソッドトレース / チェック結果 タブでその時間帯のデータを確認
```

録画ファイルは `./recordings/log-<タイムスタンプ>.log` に保存されます。

---

## プラグイン開発

外部プラグインは `./plugins/` ディレクトリに JAR を配置するだけで読み込まれます。
Plugins メニューに自動的に追加され、ユーザーが ON / OFF できます。

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

## 設定ファイル

`~/.logviewer/config.properties` に以下の設定が自動保存されます。

| キー | 内容 |
|---|---|
| `enabled.plugins` | 有効なプラグイン ID のカンマ区切りリスト |
| `recent.count` | 最近開いたファイルの件数 |
| `recent.N.path` | N 番目のファイルパス |
| `recent.N.tail` | tail モードで開いたか |
| `recent.N.tailOnly` | 末尾からの監視のみか |

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
    ├── FieldExtractorPlugin      (priority -20, headless)
    ├── LevelNormalizerPlugin     (priority -10, headless)
    ├── RawStreamPlugin           (priority  0)
    ├── GridPlugin                (priority  0)
    ├── HtmlPlugin                (priority  0)
    ├── StatsPlugin               (priority  0)
    ├── MethodTraceHtmlPlugin     (priority  0)
    ├── CheckResultHtmlPlugin     (priority  0)
    ├── RecorderPlugin            (priority  0)
    └── [外部プラグイン...]
             │
             ▼ SwingUtilities.invokeLater
            EDT（UI 更新）
```
