# コントリビューション

## Issue

- 不具合は対象 APK のバージョン、端末、Android バージョン、再現手順、期待結果、実際の結果を添えて報告してください。
- ログやスクリーンショットに個人情報、アカウント、カード情報、認証情報を含めないでください。
- パッチ適用時のログは、秘密情報を除いて `verification.md` のどの確認が失敗したか分かる範囲で添えてください。

## Pull Request

1. `main` から作業ブランチを作ります。
2. 変更に対応するテストまたは検証手順を追加・更新します。
3. 次を実行し、成功を確認します。

```powershell
.\gradlew.bat test build :patches:buildAndroid
python scripts/verify-patched-apk.py <patched.apk>
```

4. `CHANGELOG.md`、`findings.md`、`verification.md`、`how-to-update.md` のうち関係する文書を更新します。
5. PR には変更内容、検証結果、未検証の点を書きます。

## 禁止事項

- APK、キーストア、トークン、Cookie、個人情報をコミットしないでください。
- 公式アプリの配布物や逆コンパイル結果を大量にコミットしないでください。
- 不正利用を目的とした回避や、他人のアカウント・端末を対象にした検証を行わないでください。
