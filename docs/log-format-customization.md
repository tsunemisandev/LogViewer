# ログフォーマット変更ガイド

ログのフォーマットが変わった時に修正が必要なファイルと手順をまとめます。

---

## 現在のフォーマット（デフォルト）

```
2026-03-16 14:22:01.123 情報 [worker-1] メッセージ本文
    継続行1
    key：value
    k1/k2:v1/v2
```

| 要素 | 位置 |
|---|---|
| タイムスタンプ | 先頭 `yyyy-MM-dd HH:mm:ss[.SSS]` |
| レベル | タイムスタンプの直後（スペース区切り） |
| スレッド名 | `[` `]` で囲まれた部分 |
| メッセージ | スレッド名以降の残り全体 |
| 継続行 | タイムスタンプで始まらない行（インデントあり） |

---

## ログ解析の流れ

```
1行入力
    │
    ▼
PrefixDateStrategy          ← 「新しいレコードの開始か？」を判定
    │ 新レコード開始なら前のバッファを確定
    ▼
LineAssembler.buildRecord() ← 1行目を HEADER 正規表現でパース
    │ timestamp / level / thread / message を抽出
    ▼
FieldExtractorPlugin        ← 継続行（2行目以降）からフィールドを抽出
    │ ColonExtractor / SlashPairExtractor
    ▼
LevelNormalizerPlugin       ← level を level.normalized に正規化
    │ エラー→ERROR など
    ▼
各レンダープラグイン
```

---

## ケース別：修正ファイルと手順

---

### ケース 1：タイムスタンプのフォーマットが変わった

**例**: `2026/03/16 14:22:01` （区切りが `-` から `/` に変化）

#### 修正ファイル① — 新レコード検出パターン

`app/src/main/java/com/logviewer/pipeline/PrefixDateStrategy.java`

```java
// 変更前
private static final Pattern TIMESTAMP =
        Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

// 変更後（例: yyyy/MM/dd HH:mm:ss）
private static final Pattern TIMESTAMP =
        Pattern.compile("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}");
```

#### 修正ファイル② — ヘッダー解析パターン

`app/src/main/java/com/logviewer/pipeline/LineAssembler.java`

```java
// 変更前
private static final Pattern HEADER = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s+(\\S+)\\s+\\[([^\\]]+)\\]\\s+(.*)$"
);

// 変更後（/ 区切り対応）
private static final Pattern HEADER = Pattern.compile(
        "^(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s+(\\S+)\\s+\\[([^\\]]+)\\]\\s+(.*)$"
);
```

> **注意**: `PrefixDateStrategy` と `LineAssembler` の両方を合わせて変更すること。
> 片方だけ変えるとレコード境界の検出とフィールド抽出が食い違う。

---

### ケース 2：1行目の構造が変わった

**例**: スレッド名がなくなった / レベルの位置が変わった

```
# スレッドなし
2026-03-16 14:22:01.123 INFO メッセージ本文

# レベルが末尾
2026-03-16 14:22:01.123 [worker-1] メッセージ本文 [ERROR]
```

#### 修正ファイル — `LineAssembler.java` の HEADER と buildRecord

`app/src/main/java/com/logviewer/pipeline/LineAssembler.java`

```java
// スレッドなしの例
private static final Pattern HEADER = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s+(\\S+)\\s+(.*)$"
        //  ↑ timestamp                                                  ↑ level  ↑ message
);

private LogRecord buildRecord(List<String> lines) {
    Map<String, String> fields = new HashMap<>();
    if (!lines.isEmpty()) {
        Matcher m = HEADER.matcher(lines.get(0));
        if (m.matches()) {
            fields.put("timestamp", m.group(1));
            fields.put("level",     m.group(2));
            // thread は削除
            fields.put("message",   m.group(3)); // グループ番号も変わる
        } else {
            fields.put("message", lines.get(0));
        }
    }
    return new LogRecord(List.copyOf(lines), fields);
}
```

> キャプチャグループの番号（`m.group(1)` など）は正規表現の `(` の順番に対応する。
> 正規表現を変えたら `buildRecord` のグループ番号も必ず確認する。

---

### ケース 3：レベルの表記が変わった

**例**: `INFO` / `WARN` / `ERROR` など英語表記に統一、または新しい日本語表記が追加された

#### 修正ファイル — `LevelNormalizerPlugin.java`

`builtin-plugins/src/main/java/com/logviewer/plugin/LevelNormalizerPlugin.java`

```java
private static final Map<String, String> LEVEL_MAP = Map.of(
        // 既存
        "エラー",   "ERROR",
        "警告",     "WARN",
        "情報",     "INFO",
        "デバッグ", "DEBUG",
        // 追加例
        "致命的",   "ERROR",   // 新しい表記
        "FATAL",    "ERROR"    // 英語表記の追加
);
```

> 英語大文字表記（`INFO`, `ERROR` 等）はマップになくても `level.toUpperCase()` でそのまま正規化される。
> 小文字や略語が来る場合はマップに追加する。

---

### ケース 4：継続行のフォーマットが変わった

#### 4-1: コロン区切りのキーや区切り文字が変わった

**例**: `key = value`（`：` の代わりに ` = `）

#### 修正ファイル — `ColonExtractor.java`

`builtin-plugins/src/main/java/com/logviewer/plugin/extractor/ColonExtractor.java`

```java
// 変更前: key：value または key:value
private static final Pattern PATTERN =
        Pattern.compile("^([^:：/\\d][^:：]*)[:：](.+)$");

// 変更後例: key = value
private static final Pattern PATTERN =
        Pattern.compile("^([^=\\d][^=]*)=(.+)$");
```

#### 4-2: スラッシュペアのキー数や区切り文字が変わった

**例**: `k1|k2:v1|v2`（`/` の代わりに `|`）

#### 修正ファイル — `SlashPairExtractor.java`

`builtin-plugins/src/main/java/com/logviewer/plugin/extractor/SlashPairExtractor.java`

```java
// split("/") を split("\\|") に変更
String[] keys = m.group(1).split("\\|");
String[] vals = m.group(2).split("\\|");
```

#### 4-3: 全く新しいパターンの継続行が増えた

新しい `FieldExtractor` 実装クラスを追加して `FieldExtractorPlugin` に登録する。

**① 新しい Extractor を作る**

`builtin-plugins/src/main/java/com/logviewer/plugin/extractor/MyExtractor.java`

```java
public class MyExtractor implements FieldExtractor {
    private static final Pattern PATTERN = Pattern.compile("...");

    @Override
    public void extract(String line, Map<String, String> fields) {
        Matcher m = PATTERN.matcher(line);
        if (!m.matches()) return;
        fields.put("myKey", m.group(1));
    }
}
```

**② `FieldExtractorPlugin` に追加する**

`builtin-plugins/src/main/java/com/logviewer/plugin/FieldExtractorPlugin.java`

```java
private final List<FieldExtractor> extractors = List.of(
        new SlashPairExtractor(),
        new ColonExtractor(),
        new MyExtractor()   // ← 追加
);
```

---

## 修正後のビルド手順

```bash
# builtin-plugins を再ビルドして plugins/ に配置
./gradlew :app:copyPlugins

# 動作確認（アプリ起動）
./gradlew :app:run
```

---

## 動作確認チェックリスト

修正後に以下を確認する。

- [ ] Grid タブで `Timestamp` 列に正しい値が入っている
- [ ] Grid タブで `Level` 列に正しい値が入っている（`エラー` ではなく `ERROR` になっているか）
- [ ] Grid タブの行カラーが正しく色付けされている（ERROR=赤, WARN=黄, INFO=白）
- [ ] 複数行ログが1レコードにまとまっている（Lines 列が `1` より大きい）
- [ ] チェック結果プラグインにデータが表示される（継続行の抽出が正しいか）
- [ ] メソッドトレースプラグインに行が表示される（開始/終了のペアが成立しているか）

---

## ファイル早見表

| 変更内容 | 修正ファイル |
|---|---|
| タイムスタンプのフォーマット | `PrefixDateStrategy.java` **と** `LineAssembler.java` |
| 1行目の構造（項目の増減・順序） | `LineAssembler.java`（HEADER 正規表現 + buildRecord） |
| レベル表記の追加・変更 | `LevelNormalizerPlugin.java` |
| コロン区切り継続行の変更 | `ColonExtractor.java` |
| スラッシュペア継続行の変更 | `SlashPairExtractor.java` |
| 新しいパターンの継続行追加 | 新 Extractor クラス作成 → `FieldExtractorPlugin.java` に登録 |
