# AmazonPhotoPicker

Amazon Photos (https://www.amazon.co.jp/photos/) の画像を、他アプリのファイル選択（`GET_CONTENT` / `PICK`）から選べるようにするAndroidアプリ。

## 使い方
1. 他アプリで画像を添付する際に「Amazon Photo Picker」を選ぶ（ランチャーから起動した場合は選択画像を共有できる）
2. アプリ内のブラウザ（GeckoView）でAmazonにログインし、画像を長押し → 確認ダイアログで「選択」
3. 下部の「選択中 n件」で選択中の画像を確認・削除
4. 「完了」で呼び出し元に画像を返す

- Amazon Photosのサムネイルを長押しした場合は、元画像 → 大きいサムネイル → 表示中の画像 の順で取得を試みる
- 表示が崩れる場合はメニューの「PC版表示」を試す

## 配布
- PR: デバッグAPKをビルドし、GitHub Artifacts / S3 のダウンロードリンクとQRコードをPRにコメントする
- main へのpush: debug / release APK をビルドし、GitHub Release（`YYYY-MM-DD_HH-MM`）として公開する
- 配布するAPKはすべて Secret `DEBUG_KEYSTORE_BASE64`（base64化したkeystore）の鍵で署名する（上書きインストール可能）。未設定の場合はCIが失敗する
