# CLAUDE.md

このリポジトリではAndroidのpdf閲覧アプリ開発をしています。

ステートレスであり、徹底的にユーザーデータを破棄し続けることを設計思想とします。
キャッシュさえもなるべく作成せず、自動で生成されたものについてはこまめに削除します。

プロジェクトルートで`build_android.sh`を実行するとビルド&インストールできます。

---

## 実装済み内容

### アーキテクチャ

- **言語**: Kotlin
- **PDF表示**: Android組み込みの `android.graphics.pdf.PdfRenderer`（外部ライブラリ不使用、キャッシュ生成なし）
- **テキスト検索**: `com.tom-roush:pdfbox-android:2.0.27.0`（Maven Central）でページごとにテキスト抽出
- **ページ表示**: `RecyclerView` + `LinearLayoutManager`（縦スクロールのみ、横スクロール禁止）
- **非同期処理**: Kotlinコルーチン（`lifecycleScope` + `Dispatchers.Default`）

### ファイル構成

- `MainActivity.kt` - PDF読み込み、検索UI、ライフサイクル管理
- `PdfPageAdapter.kt` - RecyclerViewアダプター。ページをビットマップとしてレンダリング
- `res/layout/activity_main.xml` - ツールバー＋検索バー＋RecyclerView
- `res/layout/item_pdf_page.xml` - 1ページ分のViewHolder

### 外部アプリからの呼び出し

`AndroidManifest.xml` に `ACTION_VIEW` + `application/pdf` のintent-filterを設定済み。
ファイルマネージャーなどからPDFを選ぶとこのアプリで開ける。

### ステートレス設計の実装箇所

- `android:noHistory="true"` / `android:excludeFromRecents="true"` でセッション履歴を残さない
- `android:allowBackup="false"` でバックアップ無効
- `onPause()` で `cacheDir` / `externalCacheDir` を全削除
- `onDestroy()` で `PdfRenderer` と `ParcelFileDescriptor` をクローズ
- `SharedPreferences` は一切使用しない

### 検索機能

- ツールバーの虫眼鏡アイコンで検索バーを表示/非表示
- pdfbox-androidでページごとにテキスト抽出し、キーワードを含むページ番号リストを返す
- 前/次ボタンで該当ページに `smoothScrollToPosition()` でジャンプ
- 検索はIOスレッドで実行、キャンセル可能（`Job`管理）

### 既知の制限

- ピンチズーム未実装（将来の課題）
- テキスト検索はページ単位のヒット（文字列ハイライト表示なし）
- pdfbox-androidはパスワード付きPDFに対応しているが、エラー時は単に結果0件になる
