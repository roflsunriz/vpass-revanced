# 調査知見

## 対象と保護機構

- 対象は `com.smbc_card.vpass`（minSdk 29、targetSdk 36）。APK は単一 APK で、5.14.1 は 6 DEX・arm64/armeabi-v7a/x86/x86_64 のネイティブライブラリを含む。
- Java 側に DexGuard 系の改ざん検知がある。`com.smbc_card.vpass.TamperAction` に次のコールバックが存在する。
  - `checksumTamperAction`, `signatureCheckTamperAction`, `resourceVerificationTamperAction`
  - `rootDetectionTamperAction`, `emulatorDetectionTamperAction`, `debuggerDetectionTamperAction`
  - `hookDetectionTamperAction`, `dynamicInstrumentationDetectionTamperAction`
- これらのコールバックは Java から直接呼ばれず、ネイティブ／リフレクション経由で呼ばれる。Java の呼び出し元を探しても見つからない。
- `VpassApplication` に改ざんフラグ（boolean）とエラーコード（String）があり、`BaseActivity.onCreate` がフラグ getter を呼ぶ。フラグが true なら Root エラー画面を表示して return する。
- `BaseActivity.onCreate` は本番フラグが true のとき `Settings.Global.getInt(contentResolver, "adb_enabled")` を読み、0 以外なら USB デバッグのブロック画面を表示して return する。
- `Play Integrity` / `SafetyNet` の利用は見つからない。`com.google.android.gms.safetynet` のクラスは Crashlytics の依存に含まれるだけで、起動を止める判定には使われていない。
- ネイティブライブラリ `libjnleeeqeor.so` などは文字列がなく、難読化・暗号化されている。再署名後に `SplashActivity.onCreate` から `kh` パッケージ経由でこのライブラリのコードが実行され、`SIGSEGV (SEGV_ACCERR)` でクラッシュする。

## 安定して使える fingerprint

- 改ざんコールバック: `Lcom/smbc_card/vpass/TamperAction;` の `<init>` / `<clinit>` 以外の全メソッド。バージョンによって static / instance が混在するため、引数・戻り値で絞らず本体を `return-void` にする。
- 改ざんフラグ getter: `BaseActivity.onCreate` から最初に呼ばれる `Lcom/smbc_card/vpass/VpassApplication;->...()Z`。getter が読む `IGET_BOOLEAN` のフィールドを特定し、getter を `return false` にする。
- 改ざんフラグ setter: 同じフィールドに `IPUT_BOOLEAN` / `SPUT_BOOLEAN` するメソッドから `<init>` / `<clinit>` を除外して `return-void` にする。5.3.0 ではコンストラクタがフラグを false で初期化するため、コンストラクタを無効化すると `VerifyError` になる。
- USB デバッグ検査: `BaseActivity.onCreate` の `Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I`。直後の `MOVE_RESULT` と同じレジスタを、次の 4 命令以内の `IF_EQZ` が使うことを確認してから、`MOVE_RESULT` の直後に `const/16 vX, 0` を挿入する。文字列 `adb_enabled` はバージョンによって暗号化されるため、文字列一致には頼らない。
- 署名検証初期化: `SplashActivity.onCreate` の先頭で呼ばれる `Lkh/...;->...()V` は 2 つある。そのうち、同じクラス内の引数 1 個・戻り値 `Z` のメソッドを呼ぶものが署名検証。この `()V` メソッドを `return-void` にする。もう一方（文字列復号などの初期化）は残す。
  - 5.7.0〜5.14.1 では `Lkh/я☰;->⠁E()V` と `Lkh/я☰;->сE(Lkh/...;)Z` が対応する。5.3.0 と 5.9.0 はクラス名が異なるため、名前を固定しない。
  - 署名検証の boolean を true に固定するだけでは不十分。再署名後のクラッシュは初期化メソッド側のネイティブ処理で起きるため、初期化自体を止める。

## 実装上の注意

- ReVanced の `MutableMethodImplementation.tryBlocks` は変更できない。メソッド本体を置換するときは `MutableMethodImplementation(original.registerCount)` を新しく作って `implementation` に入れ替える。古い try/catch を残すと `VerifyError: bad exception entry` になる。
- ReVanced CLI の `--exclusive -e "Vpass ReVanced"` は依存パッチを無効扱いにする。依存パッチが失敗すると本体も失敗する。CLI では `-e "改ざん・Root検知の無効化" -e "USBデバッグ検知の無効化"` も明示する。ReVanced Manager は依存関係を自動で有効にする。
- ReVanced CLI の署名は BouncyCastle の BKS キーストア（`KeyStore.getInstance("BKS", "BC")`）を要求する。JKS/PKCS12 は `Wrong version of key store.` になる。`keytool` に `-storetype BKS -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath <cli.jar>` を指定して作成する。
- ReVanced CLI の出力を `zipalign` で後から整えて `apksigner` で再署名すると、v2 署名が付かない／アラインメントが崩れる場合がある。CLI の出力をそのまま使う。
- 16KiB アラインメントは arm64 の `.so` では満たされる。5.14.1 は `lib/mips/libjnidispatch.so`、5.7.0/5.8.3/5.9.0 は `lib/armeabi-v7a/libjnleeeqeor.so` だけが 4KiB のまま残る。これらは arm64 端末では使われない。元の 5.3.0/5.7.0 も 16KiB を満たしていない。
- 5.14.1 のパッチは DEX が 6→8 個に増える。CLI は `-Xmx5G` で実行した。ReVanced Manager の既定ヒープでは不足する可能性があるため、「Patcherを別のプロセスで実行」と 2GB 以上のヒープを推奨する。
- パッチ後の APK は別署名になる。公式アプリがインストール済みならアンインストールが必要で、アプリデータは失われる。

## 実機検証

- 端末: SH-R80P（AQUOS）、Android 16、ページサイズ 4096、`adb_enabled=1`、`development_settings_enabled=1`、root なし。
- 未パッチの 5.14.1 は「ご利用の端末のUSBデバッグがオンに設定されているため…」のダイアログを表示した。
- パッチ版は 5.3.0 / 5.7.0 / 5.8.3 / 5.9.0 / 5.11.0 / 5.14.1 のすべてで、USB デバッグのダイアログを出さずオンボーディング画面まで到達した。
- root 検知の実行時検証は、root 済み端末がないため未実施。DEX 上はフラグ getter が false を返し、Root を含む全コールバックが `return-void` になっていることを確認している。
