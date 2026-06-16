# CLAUDE.md

このリポジトリではAndroidのpdf閲覧アプリ開発をしています。

ステートレスであり、徹底的にユーザーデータを破棄し続けることを設計思想とします。
キャッシュさえもなるべく作成せず、自動で生成されたものについてはこまめに削除します。

プロジェクトルートで`build_android.sh`を実行するとビルド&インストールできます。
スクリプトは対話式で2回入力を求めるため、Claudeが実行する場合は以下のコマンドを使う：

```bash
echo -e "d\nn" | bash build_android.sh
```

- 1回目 `d/r/c` → `d`（debugビルド）
- 2回目 `y/n` → `n`（インストールはしない、端末がつながっていないことが多いため）
- インストールは手動で行う

---

## リリースビルドと署名

### 署名設定ファイル（gitignore済み）
- `keystore.properties` - パスワード・エイリアス記載（コミット不可）
- `android-stateless-pdf-viewer.jks` - キーストア本体（コミット不可）
  - エイリアス: `my-key-alias`
  - `keystore.properties` の `storeFile` は rootProject 基準の相対パスで記載

### リリースビルド
```bash
echo -e "r\nn" | bash build_android.sh
```
出力先: `app/build/outputs/apk/release/app-release.apk`

### 注意
- debugビルドのAPKが端末に入っている状態でリリースビルドをインストールしようとすると `INSTALL_FAILED_UPDATE_INCOMPATIBLE` エラーが出る
- 一度アンインストールしてから入れ直す: `adb uninstall boem.dev.statelesspdfviewer`

---

## 実装済み内容

### アーキテクチャ

- **言語**: Kotlin
- **PDF表示**: Android組み込みの `android.graphics.pdf.PdfRenderer`（外部ライブラリ不使用、キャッシュ生成なし）
- **テキスト検索**: `com.tom-roush:pdfbox-android:2.0.27.0`（Maven Central）でページごとにテキスト抽出
- **ページ表示**: `RecyclerView` + `LinearLayoutManager`（縦スクロールのみ、横スクロール禁止）
- **非同期処理**: Kotlinコルーチン（`lifecycleScope` + `Dispatchers.Default`）

### ファイル構成

- `MainActivity.kt` - PDF読み込み、メニュー、検索・ページナビ UI、ライフサイクル管理
- `PdfPageAdapter.kt` - RecyclerViewアダプター。ページをビットマップとしてレンダリング
- `res/layout/activity_main.xml` - ツールバー＋検索バー＋ページバー＋RecyclerView
- `res/layout/item_pdf_page.xml` - 1ページ分のViewHolder
- `res/menu/main_menu.xml` - ハンバーガーアイコン（`ic_menu`）1つ
- `res/menu/popup_menu.xml` - ポップアップの Search / Page 項目

### メニュー構造

ツールバー右上のハンバーガーアイコン（三本線）を押すとポップアップが出る：
- **Search** → テキスト検索バーを開く（PageバーはClose）
- **Page** → ページナビゲーションバーを開く（SearchバーはClose）

### テキスト検索バー

- pdfbox-androidでページごとにテキスト抽出し、キーワードを含むページ番号リストを返す
- 前/次ボタンで該当ページに `LinearLayoutManager.scrollToPositionWithOffset()` でジャンプ
- 検索結果カウント表示（例: `1 / 3 ページ`）、前/次ボタンの有効/無効も連動
- 検索トリガー: `IME_ACTION_SEARCH` / `IME_ACTION_DONE` / Enterキーすべて対応
- 検索はIOスレッドで実行、キャンセル可能（`Job`管理）

### ページナビゲーションバー

- 左側: ページ番号入力欄（数字入力→Enterで直接ジャンプ）
- 右側: `/ 42` のような総ページ数表示
- 上下矢印ボタンで1ページずつ移動（ボタン押下時に現在表示位置を取得して基準にする）

### スクロール余白

RecyclerViewの下部に `画面高さ / 3` のpaddingを追加（`clipToPadding=false`）。
最終ページが見切れないようにするため。

### アプリアイコン

ベクタ画像（`ic_launcher_foreground.xml` / `ic_launcher_background.xml`）。
- 背景: 濃いインディゴ（`#1A237E`）
- 前景: 白いドキュメント（右上折り返し角）＋グレーのテキスト行＋ゴールドの虫眼鏡

### 外部アプリからの呼び出し

`AndroidManifest.xml` に `ACTION_VIEW` + `application/pdf` のintent-filterを設定済み。
ファイルマネージャーなどからPDFを選ぶとこのアプリで開ける。

### ステートレス設計の実装箇所

- `android:noHistory="true"` / `android:excludeFromRecents="true"` でセッション履歴を残さない
- `android:allowBackup="false"` でバックアップ無効
- `onPause()` で `cacheDir` / `externalCacheDir` を全削除
- `onDestroy()` で `PdfRenderer` と `ParcelFileDescriptor` をクローズ
- `SharedPreferences` は一切使用しない

### 既知の制限

- ピンチズーム未実装（将来の課題）
- テキスト検索はページ単位のヒット（文字列ハイライト表示なし）
- pdfbox-androidはパスワード付きPDFに対応しているが、エラー時は単に結果0件になる

## 次回接続時の手順

1. USBでスマホと物理的に接続（スマホはデバッグモード）
2. `~/adb_connect_usb.sh`を実行
